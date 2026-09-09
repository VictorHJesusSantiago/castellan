package io.castellan.ledger.application.fraud;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every configured {@link FraudRule} against a proposed transaction, in order. A
 * {@link FraudSignal.Block} from any rule stops evaluation immediately (there's no value in
 * running further rules once the transaction is already refused, and it keeps the blocking
 * rule's reason unambiguous — no risk of a later rule's flag being confused for the actual block
 * reason). A {@link FraudSignal.Flag} does not stop evaluation — flags are cumulative, every rule
 * still gets to record its own concern even if the transaction ultimately proceeds.
 */
public final class FraudRuleEngine {

    private final List<FraudRule> rules;

    public FraudRuleEngine(List<FraudRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public FraudVerdict evaluate(FraudCheckContext context) {
        List<FraudVerdict.RaisedFlag> flags = new ArrayList<>();
        for (FraudRule rule : rules) {
            FraudSignal signal = rule.evaluate(context);
            switch (signal) {
                case FraudSignal.Allow ignored -> {
                }
                case FraudSignal.Flag flag -> flags.add(new FraudVerdict.RaisedFlag(rule.name(), flag.reason(), false));
                case FraudSignal.Block block -> {
                    flags.add(new FraudVerdict.RaisedFlag(rule.name(), block.reason(), true));
                    return new FraudVerdict(true, List.copyOf(flags));
                }
            }
        }
        return new FraudVerdict(false, List.copyOf(flags));
    }
}
