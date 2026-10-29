package net.okitsu.ysmepicfightcompat.animation;

import java.util.*;

/** Per-entity/view playback, with fades sampled from the last displayed weight. */
public final class MotionRulePlayback {
    public record Active(MotionRuleSet.Rule rule, double elapsed, float weight) {}

    private static final class State {
        MotionRuleSet.Rule rule;
        double started, changed;
        float from, target, duration;

        float weight(double now) {
            float p =
                    duration == 0
                            ? 1
                            : (float) Math.max(0, Math.min(1, (now - changed) / duration));
            return from + (target - from) * p;
        }
    }

    private final Map<String, State> states = new LinkedHashMap<>();

    public List<Active> sample(List<MotionRuleSet.Rule> selected, double now) {
        if (!Double.isFinite(now)) throw new IllegalArgumentException("Non-finite clock");
        var ids = new HashSet<String>();
        for (var rule : selected) {
            ids.add(rule.id());
            State s = states.get(rule.id());
            if (s == null) {
                s = new State();
                s.rule = rule;
                s.started = now;
                s.changed = now;
                s.target = 1;
                s.duration = rule.enterSeconds();
                states.put(rule.id(), s);
            } else if (s.target == 0) {
                s.from = s.weight(now);
                s.target = 1;
                s.changed = now;
                s.duration = rule.enterSeconds();
                s.started = now;
            }
        }
        var active = new ArrayList<Active>();
        for (var it = states.entrySet().iterator(); it.hasNext(); ) {
            State s = it.next().getValue();
            if (!ids.contains(s.rule.id()) && s.target != 0) {
                s.from = s.weight(now);
                s.target = 0;
                s.changed = now;
                s.duration = s.rule.exitSeconds();
            }
            float weight = s.weight(now) * s.rule.weight();
            if (s.target == 0 && weight <= 0) {
                it.remove();
                continue;
            }
            active.add(new Active(s.rule, Math.max(0, now - s.started), weight));
        }
        active.sort(Comparator.comparingInt(a -> a.rule().order()));
        return List.copyOf(active);
    }

    public void reset() {
        states.clear();
    }
}
