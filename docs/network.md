# network.md

Every network fact this project relies on, with the date it was read and the place it was read
from. SPEC.md section 12: **none of these may be written from memory.**

**All facts below read on 2026-09-14** unless a line says otherwise.

Anything this file marks `TODO[unverified]` has not been confirmed and must not be relied on.

---

## 1. The network: Base Sepolia

**Chosen on 2026-09-15.** `SPEC.md` section 2 requires a real test network and section 12 leaves
which one open. The evidence for all three candidates is in section 1b below; this is the pick
and why.

| | |
|---|---|
| Network | **Base Sepolia** |
| Chain ID | **84532** |
| USDC contract | **`0x036CbD53842c5426634e7929541eC2318f3dCF7e`** |
| Default RPC | `https://sepolia.base.org` |
| Explorer | `https://sepolia.basescan.org` |
| viem chain export | `baseSepolia` from `viem/chains` |

Why Base Sepolia over Ethereum Sepolia or Arbitrum Sepolia:

- **Circle issues native USDC on it** and the Circle faucet serves it - both verified from
  Circle's own pages, not assumed.
- **Gas is negligible and blocks are fast**, which matters for a ninety-second demo more than it
  matters technically.
- **viem exports the chain directly**, so the sidecar needs no hand-written chain definition and
  therefore no invented chain ID or RPC URL.
- Ethereum Sepolia's default RPC in viem is a third-party aggregator
  (`11155111.rpc.thirdweb.com`), which is one more party to depend on for a demo.

Nothing in `src/` imports any of this yet. The sidecar is day 11.

## 1b. The evidence for all three candidates

The verified evidence the choice in section 1 was made from. All three are networks Circle
issues native testnet USDC on and whose faucet serves them, so any of the three would work -
which is why the deciding reasons in section 1 are practical rather than technical.

Kept in full so the decision can be re-made rather than re-researched.

| | Base Sepolia | Ethereum Sepolia | Arbitrum Sepolia |
|---|---|---|---|
| Chain ID | `84532` | `11155111` | `421614` |
| Default RPC | `https://sepolia.base.org` | `https://11155111.rpc.thirdweb.com` | `https://sepolia-rollup.arbitrum.io/rpc` |
| USDC contract | `0x036CbD53842c5426634e7929541eC2318f3dCF7e` | `0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238` | `0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d` |
| Explorer | `https://sepolia.basescan.org` | `https://sepolia.etherscan.io` | `https://sepolia.arbiscan.io` |
| viem chain export | `baseSepolia` | `sepolia` | `arbitrumSepolia` |
| Circle faucet serves it | yes | yes | yes |

### Where each column came from

- **USDC contract addresses** — Circle's own documentation,
  <https://developers.circle.com/stablecoins/usdc-contract-addresses>, "Testnet" table,
  fetched and read 2026-09-14. That page warns in the same table that testnet USDC "has no
  financial value and is not backed by real US dollars", which is the correct property for
  this project.
- **Chain IDs, RPC URLs, explorer URLs, chain export names** — read from viem's own chain
  definition source on 2026-09-14, not from any third-party chain list:
  - `https://raw.githubusercontent.com/wevm/viem/main/src/chains/definitions/baseSepolia.ts`
  - `https://raw.githubusercontent.com/wevm/viem/main/src/chains/definitions/sepolia.ts`
  - `https://raw.githubusercontent.com/wevm/viem/main/src/chains/definitions/arbitrumSepolia.ts`

  These are the values viem itself will use when the sidecar imports a chain from
  `viem/chains`, which is why they were read from viem rather than from a chain registry.
  Note this is viem's `main` branch, not the published 2.56.5 tarball; re-check against the
  installed package when the sidecar is built on day 11.

`TODO[unverified]` — **no RPC endpoint has been shown to work from this machine.** See section 4.
A default RPC URL that appears in a source file is not the same as a reachable endpoint.

---

## 2. Faucet

<https://faucet.circle.com> — read 2026-09-14. Serves testnet USDC (also EURC and cirBTC) and
lists all three candidate networks above among its options.

Stated limit, quoted from the page: **"One request per pairing of asset and test network every
2 hours"**, and the button reads **"Send 20 USDC"**.

Two consequences worth planning around before day 11:

- 20 USDC per request caps how large a demo figure can be on a real network. The worked
  example in PROBLEM.md section 5 uses USD 10,000. The real settlement leg will therefore
  demonstrate the *mechanism* at small scale, not the example's figures. This needs to be
  stated plainly in `REAL_VS_SIMULATED.md` (SPEC.md section 18) rather than glossed.
- The two-hour cooldown means a failed day-11 attempt costs two hours, not two minutes. Fund
  the wallet before the sidecar is finished, not after.

`TODO[unverified]` — **the gas faucet.** Moving an ERC-20 needs the chain's native token for
gas, and the Circle faucet supplies USDC, not ETH. No gas faucet for Base Sepolia has been
verified: `https://portal.cdp.coinbase.com/products/faucet` is unreachable from this network for
the reason in section 4, so its existence and terms are unconfirmed.

**A wallet holding USDC and no ETH can do nothing**, so this has to be settled on the same
unblocked connection that day 11's real step needs — at the same time, in the same sitting.

---

## 3. viem

- **Latest published version: 2.56.5**, read from `https://registry.npmjs.org/viem/latest` on
  2026-09-14. Nothing is installed yet; the sidecar is day 11.
- SPEC.md section 3 names Node 20 for the sidecar. **The installed runtime is Node v24.18.1**
  and Kurgat has confirmed the installed version is the one to use, so the sidecar targets
  Node 24. Recorded here because it is a deviation from the specification text.

### Function signatures, read from viem's documentation on 2026-09-14

The sidecar needs three endpoints (SPEC.md section 12: send a payment, check a payment's
status, get a balance). These are the viem actions that back them. Each was read from the
page named, not recalled:

| Need | viem action | Read from |
|---|---|---|
| Send USDC | `writeContract` on a Wallet Client | <https://viem.sh/docs/contract/writeContract> |
| Read a balance | `readContract` on a Public Client | <https://viem.sh/docs/contract/readContract> |
| Check status, blocking | `waitForTransactionReceipt` on a Public Client | <https://viem.sh/docs/actions/public/waitForTransactionReceipt> |
| Check status, non-blocking | `getTransactionReceipt` on a Public Client | <https://viem.sh/docs/actions/public/getTransactionReceipt> |

Two details from those pages that change how the sidecar is written:

- The `writeContract` page carries an explicit warning that it "does not validate if the
  contract write will succeed" and recommends pairing it with `simulateContract` first.
- `waitForTransactionReceipt` "additionally supports Replacement detection (e.g. sped up
  Transactions)" — relevant to `AWAITING_RESOLUTION`, because a replaced transaction is
  precisely a case where the outcome is unclear rather than failed.

**ERC-20 ABI: do not hand-write one.** viem exports `erc20Abi`, confirmed present at line 369
of `https://raw.githubusercontent.com/wevm/viem/main/src/constants/abis.ts` on 2026-09-14.
Use that export rather than pasting an ABI fragment from anywhere.

`TODO[unverified]` — the exact argument shape of each action (`account`, `chain`, `address`,
`abi`, `functionName`, `args`) was seen in example code on those pages but has not been type
checked against the installed package. Confirm on day 11 by compiling against viem 2.56.5,
not by copying the examples.

---

## 4. This network blocks the RPC endpoints. Diagnosed 2026-09-15.

Day 1 recorded a TLS failure and guessed at an intercepting proxy. That guess is now confirmed,
and the conclusion is worse than a misconfiguration: **the traffic is blocked, not merely
inspected.**

### What was found

Inspecting the certificate actually presented for `sepolia.base.org`:

```
Subject : CN=sepolia.base.org, O="OpenDNS, Inc.", L=San Francisco, S=California, C=US
Issuer  : CN=Cisco Umbrella Secondary SubCA jnb-SG, O=Cisco
Valid   : 2026-09-13 to 2026-09-18
Chain   : PartialChain - could not be built to a trusted root authority
```

That certificate is not Base's. It is issued by **Cisco Umbrella**, a DNS and TLS filtering
service, from a Johannesburg point of presence.

Reading past the certificate error, purely to find out whether the traffic is permitted:

```
HTTP/1.1 303 See Other
Server: Cisco Umbrella
Location: http://sepolia.base.org/
```

A 303 to a block page. **No JSON-RPC response comes back even when the certificate is ignored.**

### What this rules in and out

- It is **not** a broken machine, a missing CA bundle, or a Node/curl quirk. Node fails for the
  same reason and so would anything else.
- It is **not** specific to one provider. Six independent RPC hosts - `sepolia.base.org`,
  `base-sepolia-rpc.publicnode.com`, `1rpc.io`, `base-sepolia.drpc.org`, `rpc.ankr.com` and
  `ethereum-sepolia-rpc.publicnode.com` - all fail identically, while `developers.circle.com`,
  `faucet.circle.com`, `raw.githubusercontent.com`, `registry.npmjs.org`, `repo1.maven.org` and
  `viem.sh` all return 200 on the same connection. The category is blocked, not the host.
- **Installing the Umbrella root CA would not help.** It would turn the TLS error into a clean
  303 to a block page. The certificate is the symptom; the policy is the cause.

### What to do about it

This is a decision for whoever administers the network, not a thing this repository can fix.
Three options, in order of how little they cost:

1. **Run day 11's real-settlement step on a different network** - a phone hotspot, or a home
   connection. This is the cheapest and needs no permission. Everything except that one step
   works here.
2. **Have the domain category allowed** in the Umbrella policy for this machine.
3. **Accept it** and ship day 11 as sidecar-complete but never run against a real network. That
   costs the "real transaction hash in the database" deliverable, which `SPEC.md` section 15 makes
   day 11's whole definition of done and section 18 lists in the recorded demonstration.

**Do not, under any circumstances, resolve this by disabling certificate verification.** Not
`curl -k`, not `NODE_TLS_REJECT_UNAUTHORIZED=0`. It would not work anyway - the block page is not
a JSON-RPC endpoint - and a treasury system shipping with TLS verification off is a worse problem
than the one being worked around. The two diagnostic requests above ignored the certificate on
purpose, once, to identify the cause, and nothing in `src/` or the sidecar will.

### How day 11 is built around this

The sidecar is written and tested **without needing the network at all**: its three endpoints are
exercised against a local stub, so the HTTP contract between Java and Node is fully proven here.
The only step that needs an unblocked connection is pointing it at a real RPC and capturing a
real transaction hash - one command, documented, runnable in a minute from anywhere that is not
behind this policy.

That keeps `SPEC.md` section 19's bet intact: a bad day on the sidecar costs a feature, not the
project.

### Consequence for USDC decimals

`TODO[unverified]` - **the deployed contract's `decimals()` has still not been read**, and it
cannot be from here.

Circle's contract source was checked as an alternative and **does not answer the question**:
in `FiatTokenV1.sol` the value is a constructor-time parameter, not a constant -

```solidity
uint8 public decimals;          // line 36
decimals = tokenDecimals;       // line 98, in initialize()
```

- so only an on-chain call tells you what the Base Sepolia deployment actually uses. `SPEC.md`
section 5 fixes USDC at scale 6 and the code follows the specification. Confirm with this, on an
unblocked network, and record the answer here with the date:

```bash
curl -s -X POST https://sepolia.base.org -H 'Content-Type: application/json'   --data '{"jsonrpc":"2.0","id":1,"method":"eth_call","params":[{"to":"0x036CbD53842c5426634e7929541eC2318f3dCF7e","data":"0x313ce567"},"latest"]}'
# expect result 0x...06
```

If it ever disagrees with `SPEC.md` section 5, that is a finding, not something to quietly adopt.

## 5. What this file does not yet contain

- The network is chosen (section 1) but **nothing in `src/` imports it yet**. The sidecar is
  day 11.
- No wallet address, and no statement about where its private key lives. That decision has not
  been made and must not be made casually — even on a testnet, a key committed to a public
  repository is a habit, and habits move to mainnet.
- No gas cost figures for any candidate network.
- No confirmation-count policy. How many blocks before a settlement is treated as final is a
  financial rule as much as a technical one, it is not in SPEC.md, and it is not guessed here.
