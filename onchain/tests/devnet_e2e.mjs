// Devnet end-to-end: two wallets play a full staked 1v1 match, then a
// timeout-path match. Run from onchain/: `npm run devnet:e2e`.
//
// Mirrors onchain/programs/orbitfall-match/src/lib.rs (consent model):
//   create_match -> join_match -> consent x2 -> settle   (winner takes pot)
//   create_match -> join_match -> (no consent) -> timeout_refund
//
// Env overrides:
//   RPC_URL          devnet RPC endpoint (default https://api.devnet.solana.com)
//   WALLET_A_SECRET  base58 secret key (default: fresh generated keypair)
//   WALLET_B_SECRET  base58 secret key (default: fresh generated keypair)
//   FUNDER_SECRET    base58 secret key of a funded wallet (used instead of airdrops)
import {
  Connection, Keypair, PublicKey, SystemProgram, Transaction,
  TransactionInstruction, sendAndConfirmTransaction,
} from '@solana/web3.js';
import { createHash } from 'node:crypto';

const PROGRAM_ID = new PublicKey('AeuAXhwzbULEoR3gi66RpFZNwDZx6i17i7gSgP1gUqeH');
const STAKE = 10_000_000; // 0.01 SOL
const log = (...a) => console.log('[e2e]', ...a);

const sha256 = (bytes) => new Uint8Array(createHash('sha256').update(bytes).digest());
const disc = (name) => sha256(new TextEncoder().encode(`global:${name}`)).slice(0, 8);
function le64(n) {
  const b = new Uint8Array(8);
  new DataView(b.buffer).setBigUint64(0, BigInt(n), true);
  return b;
}
const u8 = (v) => Uint8Array.from([v]);
const cat = (...arrs) => {
  const out = new Uint8Array(arrs.reduce((s, a) => s + a.length, 0));
  let o = 0;
  for (const a of arrs) { out.set(a, o); o += a.length; }
  return out;
};

// PDA seeds per lib.rs: match_state = ["match", creator], vault = ["vault", creator]
const matchPda = (creator) => PublicKey.findProgramAddressSync(
  [Buffer.from('match'), creator.toBuffer()], PROGRAM_ID)[0];
const vaultPda = (creator) => PublicKey.findProgramAddressSync(
  [Buffer.from('vault'), creator.toBuffer()], PROGRAM_ID)[0];

const ix = {
  createMatch(creator, stake, timeoutSec) {
    return new TransactionInstruction({
      programId: PROGRAM_ID,
      keys: [
        { pubkey: creator, isSigner: true, isMut: true },
        { pubkey: matchPda(creator), isSigner: false, isMut: true },
        { pubkey: vaultPda(creator), isSigner: false, isMut: true },
        { pubkey: SystemProgram.programId, isSigner: false, isMut: false },
      ],
      data: Buffer.from(cat(disc('create_match'), le64(stake), le64(timeoutSec))),
    });
  },
  joinMatch(joiner, creator) {
    return new TransactionInstruction({
      programId: PROGRAM_ID,
      keys: [
        { pubkey: joiner, isSigner: true, isMut: true },
        { pubkey: matchPda(creator), isSigner: false, isMut: true },
        { pubkey: vaultPda(creator), isSigner: false, isMut: true },
        { pubkey: SystemProgram.programId, isSigner: false, isMut: false },
      ],
      data: Buffer.from(cat(disc('join_match'))),
    });
  },
  consent(player, creator, winnerIndex) {
    return new TransactionInstruction({
      programId: PROGRAM_ID,
      keys: [
        { pubkey: player, isSigner: true, isMut: true },
        { pubkey: matchPda(creator), isSigner: false, isMut: true },
      ],
      data: Buffer.from(cat(disc('consent'), u8(winnerIndex))),
    });
  },
  settle(payer, creator, joiner) {
    return new TransactionInstruction({
      programId: PROGRAM_ID,
      keys: [
        { pubkey: payer, isSigner: true, isMut: true },
        { pubkey: matchPda(creator), isSigner: false, isMut: true },
        { pubkey: creator, isSigner: false, isMut: true },
        { pubkey: joiner, isSigner: false, isMut: true },
        { pubkey: vaultPda(creator), isSigner: false, isMut: true },
        { pubkey: SystemProgram.programId, isSigner: false, isMut: false },
      ],
      data: Buffer.from(cat(disc('settle'))),
    });
  },
  timeoutRefund(payer, creator, joiner) {
    return new TransactionInstruction({
      programId: PROGRAM_ID,
      keys: [
        { pubkey: payer, isSigner: true, isMut: true },
        { pubkey: matchPda(creator), isSigner: false, isMut: true },
        { pubkey: creator, isSigner: false, isMut: true },
        { pubkey: joiner, isSigner: false, isMut: true },
        { pubkey: vaultPda(creator), isSigner: false, isMut: true },
        { pubkey: SystemProgram.programId, isSigner: false, isMut: false },
      ],
      data: Buffer.from(cat(disc('timeout_refund'))),
    });
  },
};

// MatchState layout: 8 disc | creator 32 | joiner 32 | stake 8 | timeout_sec 8
//                    | deadline 8 | status 1 | bump 1 | consent_creator 1 | consent_joiner 1
async function fetchMatch(conn, creator) {
  const info = await conn.getAccountInfo(matchPda(creator));
  if (!info) return null;
  const d = info.data;
  return {
    creator: new PublicKey(d.subarray(8, 40)),
    joiner: new PublicKey(d.subarray(40, 72)),
    stake: d.readBigUInt64LE(72),
    deadline: d.readBigUInt64LE(88),
    status: d[96],
    consentCreator: d[98],
    consentJoiner: d[99],
  };
}

function assert(cond, msg) {
  if (!cond) { console.error('[e2e] FAIL:', msg); process.exit(1); }
}

const b58alphabet = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
function b58decode(s) {
  let n = 0n;
  for (const c of s) n = n * 58n + BigInt(b58alphabet.indexOf(c));
  const hex = n.toString(16).padStart(2, '0');
  const raw = new Uint8Array((hex.match(/../g) || []).map((b) => parseInt(b, 16)));
  let zeros = 0;
  while (s[zeros] === '1') zeros += 1;
  return new Uint8Array([...new Uint8Array(zeros), ...raw]);
}

async function fund(conn, kp, lamports, funder) {
  if (funder) {
    const tx = new Transaction().add(
      SystemProgram.transfer({ fromPubkey: funder.publicKey, toPubkey: kp.publicKey, lamports }),
    );
    await sendAndConfirmTransaction(conn, tx, [funder], { commitment: 'confirmed' });
    return;
  }
  for (let i = 0; i < 6; i += 1) {
    try {
      const sig = await conn.requestAirdrop(kp.publicKey, lamports);
      await conn.confirmTransaction(sig, 'confirmed');
      return;
    } catch (e) {
      log(`airdrop attempt ${i + 1} failed (${String(e.message || e).slice(0, 80)}), retrying`);
      await new Promise((r) => setTimeout(r, 4000));
    }
  }
  assert(false, 'could not fund wallet via airdrop; set FUNDER_SECRET');
}

async function send(conn, payer, instruction) {
  const tx = new Transaction().add(instruction);
  tx.feePayer = payer.publicKey;
  tx.recentBlockhash = (await conn.getLatestBlockhash('confirmed')).blockhash;
  tx.sign(payer);
  const sig = await conn.sendRawTransaction(tx.serialize(), { skipPreflight: false });
  await conn.confirmTransaction(sig, 'confirmed');
  return sig;
}

async function main() {
  const rpc = process.env.RPC_URL || 'https://api.devnet.solana.com';
  const conn = new Connection(rpc, 'confirmed');
  log('RPC:', rpc);

  const mkKp = (env) => (process.env[env]
    ? Keypair.fromSecretKey(b58decode(process.env[env])) : Keypair.generate());
  const A = mkKp('WALLET_A_SECRET');
  const B = mkKp('WALLET_B_SECRET');
  const funder = process.env.FUNDER_SECRET
    ? Keypair.fromSecretKey(b58decode(process.env.FUNDER_SECRET)) : null;
  log('wallet A (player 1):', A.publicKey.toBase58());
  log('wallet B (player 2):', B.publicKey.toBase58());

  const prog = await conn.getAccountInfo(PROGRAM_ID);
  assert(prog, `program not deployed on cluster: ${PROGRAM_ID.toBase58()}`);

  await fund(conn, A, 3 * STAKE + 100_000, funder);
  await fund(conn, B, 3 * STAKE + 100_000, funder);

  // ---------- Flow 1: full match, creator wins, both consent, creator settles ----------
  log('--- flow 1: full staked match (consensus settle) ---');
  const a0 = await conn.getBalance(A.publicKey);
  const b0 = await conn.getBalance(B.publicKey);

  log('create_match (stake 0.01 SOL, timeout 3600s)');
  console.log('  sig:', await send(conn, A, ix.createMatch(A.publicKey, STAKE, 3600)));
  log('join_match');
  console.log('  sig:', await send(conn, B, ix.joinMatch(B.publicKey, A.publicKey)));

  let m = await fetchMatch(conn, A.publicKey);
  assert(m && m.status === 1, 'match should be Active after join');

  log('consent A (creator) winner=0(creator)');
  console.log('  sig:', await send(conn, A, ix.consent(A.publicKey, A.publicKey, 0)));
  log('consent B (joiner) winner=0(creator)');
  console.log('  sig:', await send(conn, B, ix.consent(B.publicKey, A.publicKey, 0)));

  m = await fetchMatch(conn, A.publicKey);
  assert(m.consentCreator === 0 && m.consentJoiner === 0, 'consent bits not recorded onchain');

  log('settle (single signer: A)');
  console.log('  sig:', await send(conn, A, ix.settle(A.publicKey, A.publicKey, B.publicKey)));

  const a1 = await conn.getBalance(A.publicKey);
  const b1 = await conn.getBalance(B.publicKey);
  const feeTol = 20_000; // a few tx fees of slack
  assert(Math.abs((a1 - a0) - STAKE) < feeTol, `winner delta ${a1 - a0} != ~+${STAKE}`);
  assert(Math.abs((b1 - b0) + STAKE) < feeTol, `joiner delta ${b1 - b0} != ~-${STAKE}`);
  log(`winner net ${a1 - a0 >= 0 ? '+' : ''}${a1 - a0} lamports, joiner net ${b1 - b0} (stake ${STAKE}, pot ${2 * STAKE} to winner)`);
  log('flow 1 OK');

  // ---------- Flow 2: timeout path, no consent, refund after deadline ----------
  log('--- flow 2: timeout path (timeout=10s, no consent) ---');
  // B creates against A this time (also exercises reversed roles)
  log('create_match (timeout 10s)');
  console.log('  sig:', await send(conn, B, ix.createMatch(B.publicKey, STAKE, 10)));
  log('join_match');
  console.log('  sig:', await send(conn, A, ix.joinMatch(A.publicKey, B.publicKey)));

  // early refund must be rejected (deadline not passed)
  let earlyRejected = false;
  try {
    await send(conn, B, ix.timeoutRefund(B.publicKey, B.publicKey, A.publicKey));
  } catch (e) { earlyRejected = true; log('early timeout_refund correctly rejected'); }
  assert(earlyRejected, 'timeout_refund before deadline must fail');

  log('waiting 16s past deadline...');
  await new Promise((r) => setTimeout(r, 16_000));

  const va0 = await conn.getBalance(vaultPda(B.publicKey));
  assert(va0 === 2 * STAKE, `vault should hold 2*stake, got ${va0}`);

  log('timeout_refund (any single signer may claim)');
  console.log('  sig:', await send(conn, B, ix.timeoutRefund(B.publicKey, B.publicKey, A.publicKey)));

  const va1 = await conn.getBalance(vaultPda(B.publicKey));
  assert(va1 === 0, `vault should be drained after refund, got ${va1}`);
  m = await fetchMatch(conn, B.publicKey);
  assert(m && m.consentCreator === 255 && m.consentJoiner === 255, 'no consent should have been recorded');
  log('flow 2 OK — both stakes refunded by timeout backstop');

  log('ALL E2E CHECKS PASSED');
}

main().catch((e) => { console.error('[e2e] ERROR:', e); process.exit(1); });
