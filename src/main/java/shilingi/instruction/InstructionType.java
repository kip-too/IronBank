package shilingi.instruction;

/**
 * What an instruction tells a rail to do. The two kinds correspond to SPEC.md section 12's two
 * outbound adapters - there is no third, because there is no third rail.
 */
public enum InstructionType {

    /**
     * Give up dollars for shillings. Goes to the settlement adapter, which talks to the Node
     * sidecar (SPEC.md section 12).
     */
    CONVERSION,

    /**
     * Send shillings to a beneficiary. Goes to the payout adapter, which is mocked - see
     * PROBLEM.md section 7 for why that boundary is a design constraint and not a shortcut.
     */
    PAYOUT
}
