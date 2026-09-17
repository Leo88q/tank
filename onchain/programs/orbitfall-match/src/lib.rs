//! DROPFIRE staked 1v1 match escrow (Phase 4, devnet-first).
//!
//! Flow: create_match (stake escrowed) -> join_match (stake escrowed, timer starts)
//!   -> consent(winner) by each side (single-signer, MWA-friendly)
//!   -> settle (anyone, once both consents match; pot to winner)
//!   | timeout_refund (after deadline anyone refunds both stakes)
//!   | cancel (while still Open, creator withdraws).
//!
//! Every player action is a single-signer transaction, which maps 1:1 onto
//! Solana Mobile Wallet Adapter sessions (one device = one wallet).
//!
//! Escrow lamports live in a data-less vault PDA (system transfers out of an
//! account carrying data are rejected by the System program).
//!
//! Trust model: consensual result (both consents recorded onchain) + timeout
//! backstop. Full deterministic onchain validation is Phase 5's decision point.

use anchor_lang::prelude::*;
use anchor_lang::system_program;

declare_id!("AeuAXhwzbULEoR3gi66RpFZNwDZx6i17i7gSgP1gUqeH");

#[constant]
pub const MATCH_SEED: &[u8] = b"match";
#[constant]
pub const VAULT_SEED: &[u8] = b"vault";
#[constant]
pub const NO_CONSENT: u8 = 255;

#[program]
pub mod orbitfall_match {
    use super::*;

    pub fn create_match(ctx: Context<CreateMatch>, stake: u64, timeout_sec: i64) -> Result<()> {
        require!(stake > 0, MatchError::ZeroStake);
        require!(timeout_sec >= 60, MatchError::TimeoutTooShort);

        let m = &mut ctx.accounts.match_state;
        m.creator = ctx.accounts.creator.key();
        m.joiner = Pubkey::default();
        m.stake = stake;
        m.timeout_sec = timeout_sec;
        m.deadline = 0;
        m.status = MatchStatus::Open as u8;
        m.bump = ctx.bumps.match_state;
        m.consent_creator = NO_CONSENT;
        m.consent_joiner = NO_CONSENT;

        system_program::transfer(
            CpiContext::new(
                ctx.accounts.system_program.to_account_info(),
                system_program::Transfer {
                    from: ctx.accounts.creator.to_account_info(),
                    to: ctx.accounts.vault.to_account_info(),
                },
            ),
            stake,
        )?;

        emit!(MatchCreated {
            creator: m.creator,
            stake,
            timeout_sec
        });
        Ok(())
    }

    pub fn join_match(ctx: Context<JoinMatch>) -> Result<()> {
        let m = &mut ctx.accounts.match_state;
        require!(m.status == MatchStatus::Open as u8, MatchError::NotOpen);
        require!(
            ctx.accounts.joiner.key() != m.creator,
            MatchError::SelfMatch
        );

        m.joiner = ctx.accounts.joiner.key();
        m.deadline = Clock::get()?.unix_timestamp + m.timeout_sec;
        m.status = MatchStatus::Active as u8;

        system_program::transfer(
            CpiContext::new(
                ctx.accounts.system_program.to_account_info(),
                system_program::Transfer {
                    from: ctx.accounts.joiner.to_account_info(),
                    to: ctx.accounts.vault.to_account_info(),
                },
            ),
            m.stake,
        )?;

        emit!(MatchJoined {
            creator: m.creator,
            joiner: m.joiner,
            deadline: m.deadline
        });
        Ok(())
    }

    /// Each side records its consent to the result (single-signer).
    /// winner: 0 = creator wins, 1 = joiner wins.
    pub fn consent(ctx: Context<Consent>, winner: u8) -> Result<()> {
        let m = &mut ctx.accounts.match_state;
        require!(m.status == MatchStatus::Active as u8, MatchError::NotActive);
        require!(winner <= 1, MatchError::BadWinner);
        let player = ctx.accounts.player.key();
        if player == m.creator {
            m.consent_creator = winner;
        } else if player == m.joiner {
            m.consent_joiner = winner;
        } else {
            return err!(MatchError::NotParticipant);
        }
        emit!(ConsentRecorded { player, winner });
        Ok(())
    }

    /// Anyone can settle once BOTH consents match.
    pub fn settle(ctx: Context<Settle>) -> Result<()> {
        let m = &ctx.accounts.match_state;
        require!(m.status == MatchStatus::Active as u8, MatchError::NotActive);
        require!(
            m.consent_creator == m.consent_joiner && m.consent_creator != NO_CONSENT,
            MatchError::NoConsensus
        );
        let winner = m.consent_creator;

        let pot = m.stake.checked_mul(2).unwrap();
        let winner_account = if winner == 0 {
            ctx.accounts.creator.to_account_info()
        } else {
            ctx.accounts.joiner.to_account_info()
        };

        let seeds = &[VAULT_SEED, m.creator.as_ref(), &[ctx.bumps.vault]];
        system_program::transfer(
            CpiContext::new_with_signer(
                ctx.accounts.system_program.to_account_info(),
                system_program::Transfer {
                    from: ctx.accounts.vault.to_account_info(),
                    to: winner_account,
                },
                &[&seeds[..]],
            ),
            pot,
        )?;

        emit!(MatchSettled {
            creator: m.creator,
            joiner: m.joiner,
            winner,
            pot
        });
        Ok(())
    }

    /// After the deadline anyone can unblock the escrow: both stakes returned.
    pub fn timeout_refund(ctx: Context<TimeoutRefund>) -> Result<()> {
        let m = &ctx.accounts.match_state;
        require!(m.status == MatchStatus::Active as u8, MatchError::NotActive);
        require!(
            Clock::get()?.unix_timestamp > m.deadline,
            MatchError::NotExpired
        );

        let seeds = &[VAULT_SEED, m.creator.as_ref(), &[ctx.bumps.vault]];
        for player in [&ctx.accounts.creator, &ctx.accounts.joiner] {
            system_program::transfer(
                CpiContext::new_with_signer(
                    ctx.accounts.system_program.to_account_info(),
                    system_program::Transfer {
                        from: ctx.accounts.vault.to_account_info(),
                        to: player.to_account_info(),
                    },
                    &[&seeds[..]],
                ),
                m.stake,
            )?;
        }

        emit!(MatchRefunded {
            creator: m.creator,
            joiner: m.joiner
        });
        Ok(())
    }

    /// While nobody joined, creator can withdraw and close.
    pub fn cancel(ctx: Context<Cancel>) -> Result<()> {
        let m = &ctx.accounts.match_state;
        require!(m.status == MatchStatus::Open as u8, MatchError::NotOpen);

        let seeds = &[VAULT_SEED, m.creator.as_ref(), &[ctx.bumps.vault]];
        system_program::transfer(
            CpiContext::new_with_signer(
                ctx.accounts.system_program.to_account_info(),
                system_program::Transfer {
                    from: ctx.accounts.vault.to_account_info(),
                    to: ctx.accounts.creator.to_account_info(),
                },
                &[&seeds[..]],
            ),
            m.stake,
        )?;
        Ok(())
    }
}

#[derive(Accounts)]
pub struct CreateMatch<'info> {
    #[account(mut)]
    pub creator: Signer<'info>,
    #[account(
        init,
        payer = creator,
        space = 8 + MatchState::INIT_SPACE,
        seeds = [MATCH_SEED, creator.key().as_ref()],
        bump
    )]
    pub match_state: Account<'info, MatchState>,
    /// CHECK: data-less escrow PDA; lamports-only system account
    #[account(mut, seeds = [VAULT_SEED, creator.key().as_ref()], bump)]
    pub vault: UncheckedAccount<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct JoinMatch<'info> {
    #[account(mut)]
    pub joiner: Signer<'info>,
    #[account(
        mut,
        seeds = [MATCH_SEED, match_state.creator.as_ref()],
        bump = match_state.bump
    )]
    pub match_state: Account<'info, MatchState>,
    /// CHECK: data-less escrow PDA
    #[account(mut, seeds = [VAULT_SEED, match_state.creator.as_ref()], bump)]
    pub vault: UncheckedAccount<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct Consent<'info> {
    #[account(mut)]
    pub player: Signer<'info>,
    #[account(
        mut,
        seeds = [MATCH_SEED, match_state.creator.as_ref()],
        bump = match_state.bump
    )]
    pub match_state: Account<'info, MatchState>,
}

#[derive(Accounts)]
pub struct Settle<'info> {
    /// any payer may settle once consensus exists
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(
        mut,
        seeds = [MATCH_SEED, match_state.creator.as_ref()],
        bump = match_state.bump,
        constraint = creator.key() == match_state.creator @ MatchError::WrongCreator,
        constraint = joiner.key() == match_state.joiner @ MatchError::WrongJoiner,
        close = creator
    )]
    pub match_state: Account<'info, MatchState>,
    /// CHECK: validated against match_state.creator
    #[account(mut)]
    pub creator: AccountInfo<'info>,
    /// CHECK: validated against match_state.joiner
    #[account(mut)]
    pub joiner: AccountInfo<'info>,
    /// CHECK: data-less escrow PDA
    #[account(mut, seeds = [VAULT_SEED, match_state.creator.as_ref()], bump)]
    pub vault: UncheckedAccount<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct TimeoutRefund<'info> {
    /// any payer may trigger the refund after the deadline
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(
        mut,
        seeds = [MATCH_SEED, match_state.creator.as_ref()],
        bump = match_state.bump,
        constraint = creator.key() == match_state.creator @ MatchError::WrongCreator,
        constraint = joiner.key() == match_state.joiner @ MatchError::WrongJoiner
    )]
    pub match_state: Account<'info, MatchState>,
    /// CHECK: validated against match_state.creator
    #[account(mut)]
    pub creator: AccountInfo<'info>,
    /// CHECK: validated against match_state.joiner
    #[account(mut)]
    pub joiner: AccountInfo<'info>,
    /// CHECK: data-less escrow PDA
    #[account(mut, seeds = [VAULT_SEED, match_state.creator.as_ref()], bump)]
    pub vault: UncheckedAccount<'info>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct Cancel<'info> {
    #[account(
        mut,
        constraint = creator.key() == match_state.creator @ MatchError::WrongCreator
    )]
    pub creator: Signer<'info>,
    #[account(
        mut,
        seeds = [MATCH_SEED, match_state.creator.as_ref()],
        bump = match_state.bump,
        close = creator
    )]
    pub match_state: Account<'info, MatchState>,
    /// CHECK: data-less escrow PDA
    #[account(mut, seeds = [VAULT_SEED, match_state.creator.as_ref()], bump)]
    pub vault: UncheckedAccount<'info>,
    pub system_program: Program<'info, System>,
}

#[account]
#[derive(InitSpace)]
pub struct MatchState {
    pub creator: Pubkey,
    pub joiner: Pubkey,
    pub stake: u64,
    pub timeout_sec: i64,
    pub deadline: i64,
    pub status: u8,
    pub bump: u8,
    pub consent_creator: u8,
    pub consent_joiner: u8,
}

#[repr(u8)]
pub enum MatchStatus {
    Open = 0,
    Active = 1,
}

#[event]
pub struct MatchCreated {
    pub creator: Pubkey,
    pub stake: u64,
    pub timeout_sec: i64,
}

#[event]
pub struct MatchJoined {
    pub creator: Pubkey,
    pub joiner: Pubkey,
    pub deadline: i64,
}

#[event]
pub struct ConsentRecorded {
    pub player: Pubkey,
    pub winner: u8,
}

#[event]
pub struct MatchSettled {
    pub creator: Pubkey,
    pub joiner: Pubkey,
    pub winner: u8,
    pub pot: u64,
}

#[event]
pub struct MatchRefunded {
    pub creator: Pubkey,
    pub joiner: Pubkey,
}

#[error_code]
pub enum MatchError {
    ZeroStake,
    TimeoutTooShort,
    NotOpen,
    NotActive,
    SelfMatch,
    BadWinner,
    NotExpired,
    WrongCreator,
    WrongJoiner,
    NotParticipant,
    NoConsensus,
}
