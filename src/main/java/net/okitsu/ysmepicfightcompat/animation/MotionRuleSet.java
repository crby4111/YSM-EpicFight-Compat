package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.*;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Immutable, renderer-independent user motion policy. No implicit combat vetoes. */
public final class MotionRuleSet {
    public enum Blend {
        REPLACE,
        ADDITIVE
    }

    public enum Channel {
        ROTATION,
        POSITION,
        SCALE
    }

    public record Facts(Map<String, String> values, Set<String> animations) {
        public Facts {
            values = Map.copyOf(values);
            animations = Set.copyOf(animations);
        }
    }

    public record Source(String kind, String name, boolean associatedLayers, boolean nativeHead,
                         String bodyOrientation) {
        public Source(String kind, String name, boolean associatedLayers, boolean nativeHead) {
            this(kind, name, associatedLayers, nativeHead,
                    kind.equals("authored_state") ? name : kind.equals("controller") ? "movement" : "none");
        }
    }

    public record Rule(
            String id,
            boolean override,
            int priority,
            int order,
            Predicate<Facts> condition,
            Source source,
            Predicate<String> bones,
            Set<Channel> channels,
            Blend blend,
            float weight,
            float enterSeconds,
            float exitSeconds,
            String clock,
            String clockAnimation) {}

    public record Plan(
            Rule override, List<Rule> extras, List<String> shadowed, List<String> errors) {
        public Plan {
            extras = List.copyOf(extras);
            shadowed = List.copyOf(shadowed);
            errors = List.copyOf(errors);
        }

        public String key() {
            return (override == null ? "ef" : override.id())
                    + "|"
                    + extras.stream().map(Rule::id).toList();
        }
    }

    public static final MotionRuleSet DISABLED =
            new MotionRuleSet(false, List.of(), List.of(), "{\"enabled\":false}");
    private final boolean enabled;
    private final List<Rule> overrides, extras;
    private final String sourceJson;

    private MotionRuleSet(boolean enabled, List<Rule> overrides, List<Rule> extras,
                          String sourceJson) {
        this.enabled = enabled;
        this.overrides = List.copyOf(overrides);
        this.extras = List.copyOf(extras);
        this.sourceJson = sourceJson;
    }

    /** The accepted configuration, detached from the caller's mutable JSON tree. */
    public String sourceJson() { return sourceJson; }

    public boolean enabled() {
        return enabled;
    }

    public List<Rule> overrides() {
        return overrides;
    }

    public List<Rule> extras() {
        return extras;
    }

    public Plan resolve(Facts facts, Predicate<Source> available) {
        return resolveRules(facts, rule -> available.test(rule.source()));
    }

    public Plan resolveRules(Facts facts, Predicate<Rule> available) {
        Rule winner = null;
        var shadowed = new ArrayList<String>();
        var errors = new ArrayList<String>();
        for (Rule rule : overrides)
            if (rule.condition().test(facts)) {
                if (!available.test(rule)) {
                    errors.add(
                            rule.id()
                                    + ": source, bone binding or clock unavailable: "
                                    + rule.source().name());
                    continue;
                }
                if (winner == null) winner = rule;
                else shadowed.add(rule.id());
            }
        var active = new ArrayList<Rule>();
        for (Rule rule : extras)
            if (rule.condition().test(facts)) {
                if (available.test(rule)) active.add(rule);
                else
                    errors.add(
                            rule.id()
                                    + ": source, bone binding or clock unavailable: "
                                    + rule.source().name());
            }
        return new Plan(winner, active, shadowed, errors);
    }

    public static MotionRuleSet parse(JsonObject root) {
        keys(
                root,
                Set.of(
                        "schema_version",
                        "enabled",
                        "default_pose",
                        "overrides",
                        "extras",
                        "$comment"));
        require(integer(root, "schema_version", 1) == 1, "Unsupported motion rule schema");
        require(
                text(root, "default_pose", "epic_fight").equals("epic_fight"),
                "default_pose must be epic_fight");
        var ids = new HashSet<String>();
        var overrides = parseRules(root, "overrides", true, ids);
        var extras = parseRules(root, "extras", false, ids);
        require(ids.size() <= 128, "Too many motion rules (maximum 128)");
        overrides.sort(
                Comparator.comparingInt(Rule::priority).reversed().thenComparingInt(Rule::order));
        extras.sort(Comparator.comparingInt(Rule::order));
        return new MotionRuleSet(bool(root, "enabled", true), overrides, extras, root.toString());
    }

    private static List<Rule> parseRules(
            JsonObject root, String key, boolean override, Set<String> ids) {
        var result = new ArrayList<Rule>();
        if (!root.has(key)) return result;
        require(root.get(key).isJsonArray(), key + " must be an array");
        require(root.getAsJsonArray(key).size() <= 128, "Too many " + key);
        int declaration = 0;
        for (JsonElement raw : root.getAsJsonArray(key)) {
            require(raw.isJsonObject(), "Rule must be an object");
            var r = raw.getAsJsonObject();
            keys(
                    r,
                    Set.of(
                            "id",
                            "enabled",
                            "models",
                            "priority",
                            "order",
                            "when",
                            "source",
                            "target",
                            "blend",
                            "playback",
                            "transition"));
            String id = text(r, "id", "");
            require(
                    !id.isBlank() && id.length() <= 128 && ids.add(id),
                    "Invalid/duplicate rule id: " + id);
            int order = integer(r, "order", declaration++);
            Predicate<Facts> condition = condition(r.get("when"), 0);
            if (r.has("models")) {
                var models = matcher(r.get("models"));
                condition = condition.and(f -> models.test(f.values().getOrDefault("model", "")));
            }
            if (!bool(r, "enabled", true)) condition = f -> false;
            var source = object(r, "source");
            keys(
                    source,
                    Set.of("kind", "id", "state", "include_associated_layers", "head_tracking", "body_orientation"));
            String kind = text(source, "kind", "clip");
            require(
                    Set.of("clip", "authored_state", "controller").contains(kind),
                    "Unsupported source kind: " + kind);
            String name = text(source, kind.equals("authored_state") ? "state" : "id", "");
            require(!name.isBlank() && name.length() <= 256, "Missing animation source for " + id);
            if (kind.equals("authored_state"))
                require(override, "authored_state is a whole-body override source");
            String head =
                    text(
                            source,
                            "head_tracking",
                            kind.equals("authored_state") ? "native" : "none");
            require(Set.of("native", "none").contains(head), "Unknown head_tracking: " + head);
            boolean associated =
                    bool(source, "include_associated_layers", kind.equals("authored_state"));
            String orientation = text(source, "body_orientation", kind.equals("authored_state")
                    ? MovementAnimationType.fromConfigKey(name) == null ? "none" : name
                    : kind.equals("controller") && override ? "movement" : "none");
            require(Set.of("none", "movement", "idle", "walk", "run", "sneak_idle", "sneak_move",
                    "jump", "swim", "water_idle", "creative_flight", "elytra_flight", "crawl_idle",
                    "crawl_move", "ladder_idle", "ladder_up", "ladder_down").contains(orientation),
                    "Unknown body_orientation: " + orientation);
            require(override || orientation.equals("none"), "Extra layers cannot change body orientation");
            require(override || !associated, "Extra layers cannot implicitly include other clips");
            require(
                    override || head.equals("none"),
                    "Extra layers cannot request native head tracking");
            var target = object(r, "target");
            keys(target, Set.of("bones", "channels"));
            Predicate<String> bones =
                    target.has("bones") ? matcher(target.get("bones")) : s -> true;
            require(
                    !override || !target.has("bones"),
                    "Whole-body overrides do not accept a partial bones mask in schema 1");
            var channels = EnumSet.allOf(Channel.class);
            if (target.has("channels")) {
                channels.clear();
                require(target.get("channels").isJsonArray(), "channels must be an array");
                for (var c : target.getAsJsonArray("channels"))
                    try {
                        channels.add(Channel.valueOf(c.getAsString().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException("Unknown channel: " + c);
                    }
                require(!channels.isEmpty(), "Empty channel mask");
            }
            require(
                    !override || channels.size() == 3,
                    "Whole-body overrides require all TRS channels in schema 1");
            var blend = object(r, "blend");
            keys(blend, Set.of("mode", "weight", "reference"));
            String mode = text(blend, "mode", "replace_local_channels");
            require(
                    Set.of("additive", "replace_local_channels").contains(mode),
                    "Unknown blend mode: " + mode);
            require(!override || !mode.equals("additive"), "Override must replace the base pose");
            require(
                    text(blend, "reference", "neutral").equals("neutral"),
                    "Only the neutral authored reference is supported");
            float weight = number(blend, "weight", 1, 0, 1);
            require(
                    !override || weight == 1,
                    "Whole-body override weight must be 1; use transition durations");
            var transition = object(r, "transition");
            keys(transition, Set.of("enter_seconds", "exit_seconds"));
            var playback = object(r, "playback");
            keys(playback, Set.of("clock", "ef_animation"));
            String localName = name.startsWith("model:") ? name.substring(6) : name;
            boolean nativeParallel =
                    !kind.equals("controller")
                            && (localName.startsWith("parallel")
                                    || localName.startsWith("pre_parallel"));
            String clock = text(playback, "clock", nativeParallel ? "authored" : "independent");
            require(
                    Set.of("independent", "authored", "ef").contains(clock),
                    "Unknown clock: " + clock);
            require(
                    !nativeParallel || clock.equals("authored"),
                    "Native parallel clips require the shared authored clock");
            require(
                    !kind.equals("controller") || clock.equals("independent"),
                    "Controller sources use their activation-driven independent state clock");
            String clockAnimation = text(playback, "ef_animation", "");
            require(
                    !clock.equals("ef") || !clockAnimation.isBlank(),
                    "EF clock requires ef_animation id");
            result.add(
                    new Rule(
                            id,
                            override,
                            integer(r, "priority", 0),
                            order,
                            condition,
                            new Source(kind, name, associated, head.equals("native"), orientation),
                            bones,
                            Set.copyOf(channels),
                            mode.equals("additive") ? Blend.ADDITIVE : Blend.REPLACE,
                            weight,
                            number(transition, "enter_seconds", 0, 0, 10),
                            number(transition, "exit_seconds", 0, 0, 10),
                            clock,
                            clockAnimation));
        }
        return result;
    }

    private static Predicate<Facts> condition(JsonElement raw, int depth) {
        require(depth <= 8, "Condition nesting exceeds 8");
        if (raw == null || raw.isJsonNull()) return f -> true;
        require(raw.isJsonObject(), "Condition must be an object");
        Predicate<Facts> result = f -> true;
        for (var entry : raw.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            var value = entry.getValue();
            Predicate<Facts> part;
            if (key.equals("all") || key.equals("any")) {
                require(
                        value.isJsonArray() && value.getAsJsonArray().size() <= 32,
                        "Condition list is invalid");
                var children = new ArrayList<Predicate<Facts>>();
                for (var child : value.getAsJsonArray()) children.add(condition(child, depth + 1));
                part =
                        key.equals("all")
                                ? f -> children.stream().allMatch(p -> p.test(f))
                                : f -> children.stream().anyMatch(p -> p.test(f));
            } else if (key.equals("not")) part = condition(value, depth + 1).negate();
            else {
                require(
                        Set.of(
                                        "model",
                                        "movement",
                                        "view",
                                        "item",
                                        "ef_animation",
                                        "using_item",
                                        "attacking",
                                        "hurt",
                                        "battle_mode",
                                        "sneaking",
                                        "sprinting",
                                        "on_ground",
                                        "in_water")
                                .contains(key),
                        "Unknown condition: " + key);
                var matches = matcher(value);
                part =
                        key.equals("ef_animation")
                                ? f -> f.animations().stream().anyMatch(matches)
                                : f -> matches.test(f.values().getOrDefault(key, ""));
            }
            result = result.and(part);
        }
        return result;
    }

    private static Predicate<String> matcher(JsonElement value) {
        var patterns = new ArrayList<Pattern>();
        var values = value.isJsonArray() ? value.getAsJsonArray() : null;
        require(values == null || values.size() <= 128, "Too many selectors");
        if (values == null) patterns.add(glob(value));
        else for (var item : values) patterns.add(glob(item));
        require(!patterns.isEmpty(), "Empty selector list");
        return s -> patterns.stream().anyMatch(p -> p.matcher(s).matches());
    }

    private static Pattern glob(JsonElement value) {
        require(value.isJsonPrimitive(), "Selector must be a string/boolean");
        String s = value.getAsString();
        require(s.length() <= 256, "Selector too long");
        var pattern = new StringBuilder("^");
        for (char c : s.toCharArray())
            pattern.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
        return Pattern.compile(pattern.append('$').toString());
    }

    private static JsonObject object(JsonObject root, String key) {
        if (!root.has(key)) return new JsonObject();
        require(root.get(key).isJsonObject(), key + " must be an object");
        return root.getAsJsonObject(key);
    }

    private static String text(JsonObject o, String k, String fallback) {
        if (!o.has(k)) return fallback;
        require(
                o.get(k).isJsonPrimitive() && o.getAsJsonPrimitive(k).isString(),
                k + " must be a string");
        return o.get(k).getAsString();
    }

    private static boolean bool(JsonObject o, String k, boolean fallback) {
        if (!o.has(k)) return fallback;
        require(
                o.get(k).isJsonPrimitive() && o.getAsJsonPrimitive(k).isBoolean(),
                k + " must be boolean");
        return o.get(k).getAsBoolean();
    }

    private static int integer(JsonObject o, String k, int fallback) {
        if (!o.has(k)) return fallback;
        var v = o.get(k);
        require(v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber(), k + " must be integer");
        double n = v.getAsDouble();
        require(
                Double.isFinite(n)
                        && n == Math.rint(n)
                        && n >= Integer.MIN_VALUE
                        && n <= Integer.MAX_VALUE,
                k + " must be integer");
        return (int) n;
    }

    private static float number(JsonObject o, String k, float fallback, float min, float max) {
        if (!o.has(k)) return fallback;
        require(
                o.get(k).isJsonPrimitive() && o.getAsJsonPrimitive(k).isNumber(),
                k + " must be numeric");
        float n = o.get(k).getAsFloat();
        require(Float.isFinite(n) && n >= min && n <= max, k + " out of range");
        return n;
    }

    private static void keys(JsonObject o, Set<String> allowed) {
        for (String k : o.keySet()) require(allowed.contains(k), "Unknown field: " + k);
    }

    private static void require(boolean yes, String message) {
        if (!yes) throw new IllegalArgumentException(message);
    }
}
