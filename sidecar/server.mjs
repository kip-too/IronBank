// The settlement adapter (SPEC.md section 12).
//
// The Java core never talks to a blockchain. It talks to this, over local HTTP, and this is
// deliberately thin: three endpoints, no business logic, no knowledge of accounting. If the
// network changes, only this file changes.
//
// WHY JAVASCRIPT AT ALL
//   SPEC.md section 3: "The tooling for this network is JavaScript-first. Fighting that in Java
//   costs days we do not have." Same discipline as keeping rail-specific logic behind an adapter,
//   except the rail happens to need a different language.
//
// TWO MODES
//   stub  - answers from memory, touches no network, and is what the Java contract tests run
//           against. Deterministic: the same externalRef always gets the same fake hash.
//   live  - viem against Base Sepolia. Needs an unblocked network; see docs/network.md section 4,
//           which explains why that is not this machine.
//
//   The HTTP contract is identical in both, so everything except "did a real chain accept it" is
//   proven without a network.
//
// DEPENDENCIES
//   viem only. The HTTP server is node:http from the standard library rather than a framework -
//   three endpoints do not need one, and SPEC.md section 1 rule 6 makes every dependency a
//   decision.
//
// WHAT THIS DOES NOT DO
//   - No accounting. It does not know what a posting is.
//   - No retries of its own. Retrying is a decision the Java side makes, under rules this process
//     cannot see (see Instruction: a retry is a compile error in the wrong state).
//   - No key management beyond reading one from the environment. A testnet key in an env var is
//     appropriate for a testnet and nothing more.

import { createServer } from 'node:http';

const MODE = process.env.SHILINGI_MODE === 'live' ? 'live' : 'stub';
const PORT = Number(process.env.SHILINGI_PORT ?? 8787);

// Every one of these was read from viem's own source or from Circle's documentation on
// 2026-09-14/15 and recorded in docs/network.md. None is written from memory.
const CHAIN_NAME = 'baseSepolia';
const USDC_ADDRESS = process.env.SHILINGI_USDC_ADDRESS
  ?? '0x036CbD53842c5426634e7929541eC2318f3dCF7e';

// externalRef -> { externalRef, status, txHash, blockNumber, amountMinor, to }
//
// This is the rail's own idempotency, and it is a second line of defence rather than the first.
// The first is the unique constraint on instruction.external_ref (SPEC.md section 13). This one
// means that even if the Java side somehow sent the same reference twice, the chain sees one
// transaction - which is what F5 is about.
const sent = new Map();

let live = null;

async function initialiseLive() {
  if (live) return live;

  const { createPublicClient, createWalletClient, http, erc20Abi } = await import('viem');
  const { baseSepolia } = await import('viem/chains');
  const { privateKeyToAccount } = await import('viem/accounts');

  const key = process.env.SHILINGI_PRIVATE_KEY;
  if (!key) {
    throw new Error(
      'SHILINGI_PRIVATE_KEY is not set. Live mode signs real testnet transactions and there is ' +
      'no default - a key nobody chose is a key nobody can revoke.');
  }

  const rpcUrl = process.env.SHILINGI_RPC_URL ?? baseSepolia.rpcUrls.default.http[0];
  const account = privateKeyToAccount(key);

  live = {
    erc20Abi,
    account,
    chain: baseSepolia,
    publicClient: createPublicClient({ chain: baseSepolia, transport: http(rpcUrl) }),
    walletClient: createWalletClient({ account, chain: baseSepolia, transport: http(rpcUrl) }),
  };
  return live;
}

// --- the three endpoints ------------------------------------------------------------------

// POST /payments  { externalRef, to, amountMinor }
//
// Returns ACCEPTED, never SETTLED. SPEC.md section 12 and the payout adapter both insist on this:
// a rail that returns success synchronously lets the rest of the system be written wrongly and
// still pass its tests.
async function sendPayment(body) {
  const { externalRef, to, amountMinor } = body;

  if (!externalRef || typeof externalRef !== 'string') {
    return [400, { error: 'externalRef is required: it is what ties this back to an instruction' }];
  }
  if (!to || typeof to !== 'string') {
    return [400, { error: 'to is required' }];
  }
  if (amountMinor === undefined || amountMinor === null) {
    return [400, { error: 'amountMinor is required, as a whole number of minor units' }];
  }

  // Integer minor units over the wire, as a string. JSON numbers are IEEE 754 doubles and
  // SPEC.md section 5 forbids floating point for money anywhere - including in transit.
  const minor = BigInt(String(amountMinor));
  if (minor <= 0n) {
    return [400, { error: 'amountMinor must be positive' }];
  }

  const already = sent.get(externalRef);
  if (already) {
    // Same reference, same answer. Nothing is sent a second time.
    return [200, { ...already, idempotent: true }];
  }

  if (MODE === 'stub') {
    const record = {
      externalRef,
      status: 'ACCEPTED',
      txHash: stubHashFor(externalRef),
      blockNumber: null,
      amountMinor: minor.toString(),
      to,
      simulated: true,
    };
    sent.set(externalRef, record);
    return [202, record];
  }

  const { walletClient, publicClient, erc20Abi, account, chain } = await initialiseLive();

  // viem's writeContract docs warn it "does not validate if the contract write will succeed" and
  // recommend simulating first. Read from https://viem.sh/docs/contract/writeContract on
  // 2026-09-14 and recorded in docs/network.md section 3.
  const { request } = await publicClient.simulateContract({
    account,
    address: USDC_ADDRESS,
    abi: erc20Abi,
    functionName: 'transfer',
    args: [to, minor],
  });

  const txHash = await walletClient.writeContract(request);

  const record = {
    externalRef,
    status: 'ACCEPTED',
    txHash,
    blockNumber: null,
    amountMinor: minor.toString(),
    to,
    simulated: false,
    chain: chain.name,
    chainId: chain.id,
  };
  sent.set(externalRef, record);
  return [202, record];
}

// GET /payments/{externalRef}
//
// Re-query. Sends nothing, so it is safe to call on a payment whose outcome is unknown - which is
// exactly what SPEC.md section 7 requires of an AWAITING_RESOLUTION instruction: "It is
// re-queried", never retried.
async function paymentStatus(externalRef) {
  const record = sent.get(externalRef);
  if (!record) {
    // Not "failed". Not known. The Java side decides what that means, and its answer is
    // AWAITING_RESOLUTION rather than failure.
    return [404, { externalRef, status: 'UNKNOWN' }];
  }

  if (MODE === 'stub') {
    return [200, { ...record, status: 'SETTLED', blockNumber: '1' }];
  }

  const { publicClient } = await initialiseLive();
  try {
    const receipt = await publicClient.getTransactionReceipt({ hash: record.txHash });
    return [200, {
      ...record,
      status: receipt.status === 'success' ? 'SETTLED' : 'FAILED',
      blockNumber: receipt.blockNumber.toString(),
    }];
  } catch {
    // No receipt yet. Still in flight, and saying so is the honest answer.
    return [200, { ...record, status: 'PENDING' }];
  }
}

// GET /balance
async function balance() {
  if (MODE === 'stub') {
    return [200, {
      address: '0x0000000000000000000000000000000000000000',
      token: USDC_ADDRESS,
      amountMinor: '0',
      decimals: 6,
      simulated: true,
    }];
  }

  const { publicClient, erc20Abi, account } = await initialiseLive();

  const [amount, decimals] = await Promise.all([
    publicClient.readContract({
      address: USDC_ADDRESS, abi: erc20Abi, functionName: 'balanceOf', args: [account.address],
    }),
    publicClient.readContract({
      address: USDC_ADDRESS, abi: erc20Abi, functionName: 'decimals',
    }),
  ]);

  // Worth noticing: this is the call docs/network.md wants for confirming USDC's scale is 6.
  // Whatever the chain says is what gets reported - a disagreement with SPEC.md section 5 is a
  // finding, not something to quietly adopt.
  return [200, {
    address: account.address,
    token: USDC_ADDRESS,
    amountMinor: amount.toString(),
    decimals,
    simulated: false,
  }];
}

function stubHashFor(externalRef) {
  // Deterministic and obviously fake. Thirty-two bytes so it is the right shape, prefixed so
  // nobody mistakes one for a real hash in a log or on the demo screen.
  let h = 0n;
  for (const ch of externalRef) h = (h * 131n + BigInt(ch.codePointAt(0))) % (2n ** 240n);
  return '0x' + 'stub'.split('').map(c => c.codePointAt(0).toString(16)).join('')
    + h.toString(16).padStart(56, '0');
}

// --- plumbing -----------------------------------------------------------------------------

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const send = (status, payload) => {
    const body = JSON.stringify(payload);
    res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
    res.end(body);
  };

  try {
    if (req.method === 'GET' && url.pathname === '/health') {
      return send(200, { status: 'UP', mode: MODE, chain: CHAIN_NAME, token: USDC_ADDRESS });
    }

    if (req.method === 'POST' && url.pathname === '/payments') {
      const chunks = [];
      for await (const c of req) chunks.push(c);
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
      const [status, payload] = await sendPayment(body);
      return send(status, payload);
    }

    if (req.method === 'GET' && url.pathname.startsWith('/payments/')) {
      const ref = decodeURIComponent(url.pathname.slice('/payments/'.length));
      const [status, payload] = await paymentStatus(ref);
      return send(status, payload);
    }

    if (req.method === 'GET' && url.pathname === '/balance') {
      const [status, payload] = await balance();
      return send(status, payload);
    }

    return send(404, { error: 'no such endpoint', path: url.pathname });

  } catch (e) {
    // The Java side must be able to tell "the sidecar refused" from "the sidecar broke", because
    // one of those is an answer and the other is silence wearing a hat.
    return send(500, { error: String(e?.message ?? e) });
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`shilingi sidecar: mode=${MODE} chain=${CHAIN_NAME} listening on 127.0.0.1:${PORT}`);
});
