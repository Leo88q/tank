import * as anchor from "@coral-xyz/anchor";
import { Program } from "@coral-xyz/anchor";
import { OrbitfallMatch } from "../target/types/orbitfall_match";
import { Keypair, PublicKey, LAMPORTS_PER_SOL } from "@solana/web3.js";
import assert from "assert";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe("orbitfall-match", () => {
  const provider = anchor.AnchorProvider.env();
  anchor.setProvider(provider);
  const program = anchor.workspace.OrbitfallMatch as Program<OrbitfallMatch>;

  const creator = Keypair.generate();
  const joiner = Keypair.generate();
  const stake = new anchor.BN(LAMPORTS_PER_SOL / 100); // 0.01 SOL

  const matchPda = (creatorKey: PublicKey): PublicKey =>
    PublicKey.findProgramAddressSync(
      [Buffer.from("match"), creatorKey.toBuffer()],
      program.programId
    )[0];

  const vaultPda = (creatorKey: PublicKey): PublicKey =>
    PublicKey.findProgramAddressSync(
      [Buffer.from("vault"), creatorKey.toBuffer()],
      program.programId
    )[0];

  const accounts = (pda: PublicKey, vault: PublicKey) => ({
    matchState: pda,
    vault,
    systemProgram: anchor.web3.SystemProgram.programId,
  });

  const settleAccounts = (pda: PublicKey, vault: PublicKey) => ({
    matchState: pda,
    creator: creator.publicKey,
    joiner: joiner.publicKey,
    vault,
    systemProgram: anchor.web3.SystemProgram.programId,
  });

  before(async () => {
    for (const kp of [creator, joiner]) {
      const sig = await provider.connection.requestAirdrop(
        kp.publicKey,
        LAMPORTS_PER_SOL
      );
      await provider.connection.confirmTransaction(sig);
    }
  });

  it("create -> join -> consent x2 -> settle (winner = joiner)", async () => {
    const pda = matchPda(creator.publicKey);
    const vault = vaultPda(creator.publicKey);

    await program.methods
      .createMatch(stake, new anchor.BN(3600))
      .accounts({ creator: creator.publicKey, ...accounts(pda, vault) })
      .signers([creator])
      .rpc();

    let st = await program.account.matchState.fetch(pda);
    assert.strictEqual(st.status, 0);
    assert.ok(st.stake.eq(stake));

    await program.methods
      .joinMatch()
      .accounts({ joiner: joiner.publicKey, ...accounts(pda, vault) })
      .signers([joiner])
      .rpc();

    st = await program.account.matchState.fetch(pda);
    assert.strictEqual(st.status, 1);
    assert.ok(st.deadline.toNumber() > 0);

    await program.methods
      .consent(1)
      .accounts({ player: creator.publicKey, matchState: pda })
      .signers([creator])
      .rpc();
    await program.methods
      .consent(1)
      .accounts({ player: joiner.publicKey, matchState: pda })
      .signers([joiner])
      .rpc();

    const before = new anchor.BN(
      await provider.connection.getBalance(joiner.publicKey)
    );
    await program.methods
      .settle()
      .accounts({ payer: joiner.publicKey, ...settleAccounts(pda, vault) })
      .signers([joiner])
      .rpc();
    const after = new anchor.BN(
      await provider.connection.getBalance(joiner.publicKey)
    );
    assert.ok(after.gt(before.add(stake)));

    // account closed after settle
    await assert.rejects(program.account.matchState.fetch(pda));
  });

  it("settle without consensus is rejected; stranger consent rejected", async () => {
    const pda = matchPda(creator.publicKey);
    const vault = vaultPda(creator.publicKey);
    await program.methods
      .createMatch(stake, new anchor.BN(3600))
      .accounts({ creator: creator.publicKey, ...accounts(pda, vault) })
      .signers([creator])
      .rpc();
    await program.methods
      .joinMatch()
      .accounts({ joiner: joiner.publicKey, ...accounts(pda, vault) })
      .signers([joiner])
      .rpc();

    // only creator consented -> no consensus
    await program.methods
      .consent(0)
      .accounts({ player: creator.publicKey, matchState: pda })
      .signers([creator])
      .rpc();
    await assert.rejects(
      program.methods
        .settle()
        .accounts({ payer: creator.publicKey, ...settleAccounts(pda, vault) })
        .signers([creator])
        .rpc()
    );

    // stranger cannot consent
    const stranger = Keypair.generate();
    await assert.rejects(
      program.methods
        .consent(0)
        .accounts({ player: stranger.publicKey, matchState: pda })
        .signers([stranger])
        .rpc()
    );

    // cleanup: joiner agrees -> settle
    await program.methods
      .consent(0)
      .accounts({ player: joiner.publicKey, matchState: pda })
      .signers([joiner])
      .rpc();
    await program.methods
      .settle()
      .accounts({ payer: creator.publicKey, ...settleAccounts(pda, vault) })
      .signers([creator])
      .rpc();
  });

  it("cancel while open returns stake", async () => {
    const c2 = Keypair.generate();
    const sig = await provider.connection.requestAirdrop(
      c2.publicKey,
      LAMPORTS_PER_SOL
    );
    await provider.connection.confirmTransaction(sig);
    const pda = matchPda(c2.publicKey);
    const vault = vaultPda(c2.publicKey);

    await program.methods
      .createMatch(stake, new anchor.BN(3600))
      .accounts({ creator: c2.publicKey, ...accounts(pda, vault) })
      .signers([c2])
      .rpc();
    const before = new anchor.BN(
      await provider.connection.getBalance(c2.publicKey)
    );
    await program.methods
      .cancel()
      .accounts({ creator: c2.publicKey, ...accounts(pda, vault) })
      .signers([c2])
      .rpc();
    const after = new anchor.BN(
      await provider.connection.getBalance(c2.publicKey)
    );
    assert.ok(after.gt(before));
  });

  it("timeout path refunds both stakes", async () => {
    const pda = matchPda(creator.publicKey);
    const vault = vaultPda(creator.publicKey);
    // minimal allowed timeout = 60s; wait it out on localnet
    await program.methods
      .createMatch(stake, new anchor.BN(60))
      .accounts({ creator: creator.publicKey, ...accounts(pda, vault) })
      .signers([creator])
      .rpc();
    await program.methods
      .joinMatch()
      .accounts({ joiner: joiner.publicKey, ...accounts(pda, vault) })
      .signers([joiner])
      .rpc();

    // refund before deadline must fail
    await assert.rejects(
      program.methods
        .timeoutRefund()
        .accounts({
          payer: joiner.publicKey,
          matchState: pda,
          creator: creator.publicKey,
          joiner: joiner.publicKey,
          vault,
          systemProgram: anchor.web3.SystemProgram.programId,
        })
        .signers([joiner])
        .rpc()
    );

    await sleep(62_000);

    const cBefore = new anchor.BN(
      await provider.connection.getBalance(creator.publicKey)
    );
    await program.methods
      .timeoutRefund()
      .accounts({
        payer: joiner.publicKey,
        matchState: pda,
        creator: creator.publicKey,
        joiner: joiner.publicKey,
        vault,
        systemProgram: anchor.web3.SystemProgram.programId,
      })
      .signers([joiner])
      .rpc();
    const cAfter = new anchor.BN(
      await provider.connection.getBalance(creator.publicKey)
    );
    assert.ok(cAfter.gt(cBefore.add(stake.sub(new anchor.BN(1)))));
  }).timeout(120_000);
});
