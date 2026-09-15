package shilingi.agent;

import org.springframework.stereotype.Service;

import shilingi.clock.ClockPort;
import shilingi.instruction.Instruction;
import shilingi.instruction.InstructionRepository;
import shilingi.instruction.InstructionType;

import java.util.Optional;

/**
 * SPEC.md section 11 step 7: "Only then create the instruction."
 *
 * <p>{@link TreasuryAgent} ends at step 6 with a committed intent. This is the one thing that
 * turns a decision into something that will move money, and it is the only gate between the two.
 *
 * <h2>A refused decision produces nothing at all</h2>
 * Not an instruction marked refused. Not one in a state that will be skipped. <b>Nothing.</b>
 * SPEC.md section 11: "a proposed action that violates one is rejected regardless of how good the
 * reasoning sounds" - and a rejected action that still leaves a row somewhere is a rejection
 * somebody can undo by changing one column.
 *
 * <h2>The external reference is derived from the intent</h2>
 * {@code convert/<intent id>}. Two consequences, both deliberate:
 * <ul>
 *   <li>It is <b>deterministic</b>. Acting on the same decision twice produces the same reference,
 *       so the second attempt hits the unique constraint rather than creating a second
 *       instruction. SPEC.md section 13's constraint does the work, as it is supposed to.</li>
 *   <li>It is <b>traceable</b>. A reference in a rail's records leads back to the intent that
 *       explains it, without a join table or a lookup - which is exactly what F6 asks for.</li>
 * </ul>
 *
 * <h2>What this does not handle</h2>
 * <ul>
 *   <li><b>Nothing is submitted to a rail here.</b> This creates the instruction in CREATED.
 *       Sending it is the settlement adapter's job on day 11 and the payout adapter's on day 10.</li>
 *   <li><b>No payout instructions.</b> Only conversions, because only conversions are what
 *       SPEC.md section 11's agent decides. Paying an obligation once it is funded is a separate
 *       decision this system does not yet make.</li>
 *   <li><b>Nothing marks the obligations funded.</b> An obligation becomes FUNDED when money is
 *       actually allocated to it, and what allocation means - which obligation gets which
 *       shillings - is SPEC.md section 17 scenario 16's question and is not answered anywhere.</li>
 * </ul>
 */
@Service
public class TreasuryService {

    private final InstructionRepository instructions;
    private final ClockPort clock;

    public TreasuryService(InstructionRepository instructions, ClockPort clock) {
        this.instructions = instructions;
        this.clock = clock;
    }

    /**
     * Creates the instruction an approved decision calls for, if it calls for one.
     *
     * @return the new instruction, or empty when the decision was refused or proposed holding.
     *         Empty is a normal answer: most cycles decide to do nothing.
     */
    public Optional<Instruction.Created> act(Decision decision) {
        if (decision.refused()) {
            return Optional.empty();
        }

        if (!(decision.action() instanceof ProposedAction.Convert convert)) {
            return Optional.empty();
        }

        return Optional.of(instructions.create(
                decision.intent().id(),
                externalReferenceFor(decision),
                InstructionType.CONVERSION,
                convert.amount(),
                clock.instant()));
    }

    /** {@code convert/<intent id>}. Deterministic on purpose - see the class header. */
    public static String externalReferenceFor(Decision decision) {
        return "convert/" + decision.intent().id();
    }
}
