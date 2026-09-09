package io.castellan.ledger.application.ports;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.TenantId;

/**
 * The current balance of one account, as a read-model query — {@code ledger-infrastructure}
 * answers this from {@code AccountBalanceProjection}, a running fold over every
 * {@code TransactionPosted} posting that references the account (see that projection's own
 * docs), never by replaying anything in {@code ledger-domain}.
 *
 * <p>Sign convention, stated plainly since it's a real simplification: every account here is
 * modeled from the customer's own point of view — a CREDIT increases the balance they can spend,
 * a DEBIT decreases it, the same convention a retail banking app shows its customers — even
 * though a bank's own internal chart of accounts would carry a deposit account as a liability
 * with the natural balance running the opposite direction. Modeling full chart-of-accounts
 * semantics (asset/liability/equity/revenue/expense account types, each with their own natural-
 * balance rule) is out of scope; every account in this system uses the one customer-facing
 * convention.
 */
public interface BalancePort {

    Money currentBalance(TenantId tenantId, AccountId accountId);
}
