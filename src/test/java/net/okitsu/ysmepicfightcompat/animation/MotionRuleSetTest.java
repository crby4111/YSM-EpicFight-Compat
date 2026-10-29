package net.okitsu.ysmepicfightcompat.animation;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class MotionRuleSetTest {
    private static MotionRuleSet parse(String text) {
        return MotionRuleSet.parse(JsonParser.parseString(text).getAsJsonObject());
    }

    private static MotionRuleSet.Facts facts(String movement, boolean attacking) {
        return new MotionRuleSet.Facts(
                Map.of(
                        "model",
                        "test/cyrene",
                        "movement",
                        movement,
                        "attacking",
                        String.valueOf(attacking),
                        "view",
                        "third_person"),
                Set.of("pack:greatsword_attack"));
    }

    @Test
    void emptyRulesHaveNoImplicitNativeOwner() {
        var rules = parse("{}");
        assertTrue(rules.enabled());
        assertNull(rules.resolve(facts("swim", true), s -> true).override());
        assertTrue(rules.resolve(facts("walk", false), s -> true).extras().isEmpty());
    }

    @Test
    void explicitlyConfiguredAttackOverrideIsNotVetoed() {
        var rules =
                parse(
                        """
        {"overrides":[{"id":"attack","when":{"all":[{"attacking":true},{"ef_animation":"pack:*attack"}]},"source":{"id":"model:pose"}}]}
        """);
        assertEquals("attack", rules.resolve(facts("swim", true), s -> true).override().id());
        assertNull(rules.resolve(facts("swim", false), s -> true).override());
    }

    @Test
    void missingHighPrioritySourceFallsThroughWithoutSuppressingExtras() {
        var rules =
                parse(
                        """
        {"overrides":[
          {"id":"high","priority":20,"source":{"id":"missing"}},
          {"id":"low","priority":10,"source":{"id":"present"}}],
         "extras":[{"id":"hair","source":{"id":"present"}}]}
        """);
        var plan = rules.resolve(facts("walk", false), s -> s.name().equals("present"));
        assertEquals("low", plan.override().id());
        assertEquals(1, plan.errors().size());
        assertEquals("hair", plan.extras().get(0).id());
    }

    @Test
    void equalPriorityUsesDeclarationWhileExtraOrderIsIndependent() {
        var rules =
                parse(
                        """
        {"overrides":[{"id":"first","source":{"id":"a"}},{"id":"second","source":{"id":"b"}}],
         "extras":[{"id":"late","order":20,"source":{"id":"a"}},{"id":"early","order":10,"source":{"id":"b"}}]}
        """);
        var plan = rules.resolve(facts("walk", false), s -> true);
        assertEquals("first", plan.override().id());
        assertEquals(List.of("second"), plan.shadowed());
        assertEquals(
                List.of("early", "late"),
                plan.extras().stream().map(MotionRuleSet.Rule::id).toList());
    }

    @Test
    void nestedAnyNotAndModelGlobsAreExplicit() {
        var rules =
                parse(
                        """
        {"extras":[{"id":"a","models":["test/*"],"when":{"all":[{"any":[{"movement":"swim"},{"movement":"walk"}]},{"not":{"attacking":true}}]},"source":{"id":"model:pose"}}]}
        """);
        assertEquals(1, rules.resolve(facts("swim", false), s -> true).extras().size());
        assertTrue(rules.resolve(facts("swim", true), s -> true).extras().isEmpty());
        assertTrue(rules.resolve(facts("jump", false), s -> true).extras().isEmpty());
    }

    @Test
    void malformedRulesFailInsteadOfSilentlyBecomingAlwaysTrue() {
        for (String input :
                List.of(
                        "{\"extras\":[{\"id\":\"x\",\"when\":{\"is_attackng\":true},\"source\":{\"id\":\"a\"}}]}",
                        "{\"extras\":[{\"id\":\"x\",\"source\":{\"id\":\"a\"},\"blend\":{\"weight\":-1}}]}",
                        "{\"overrides\":[{\"id\":\"x\",\"source\":{\"id\":\"a\"},\"target\":{\"bones\":[\"Head\"]}}]}",
                        "{\"extras\":[{\"id\":\"x\",\"source\":{\"id\":\"a\",\"head_tracking\":\"native\"}}]}",
                        "{\"extras\":[{\"id\":\"x\",\"source\":{\"id\":\"a\",\"include_associated_layers\":true}}]}",
                        "{\"overrides\":[{\"id\":\"x\",\"source\":{\"id\":\"a\"}},{\"id\":\"x\",\"source\":{\"id\":\"b\"}}]}"))
            assertThrows(IllegalArgumentException.class, () -> parse(input));
    }

    @Test
    void nativeParallelCannotAccidentallyUseAnIndependentPhysicsClock() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        parse(
                                """
        {"extras":[{"id":"hair","source":{"id":"model:parallel3"},"playback":{"clock":"independent"}}]}
        """));
        var rules =
                parse("{\"extras\":[{\"id\":\"hair\",\"source\":{\"id\":\"model:parallel3\"}}]}");
        assertEquals("authored", rules.extras().get(0).clock());
    }

    @Test
    void interruptedFadeStartsFromTheCurrentWeight() {
        var rules =
                parse(
                        """
        {"extras":[{"id":"a","source":{"id":"pose"},"transition":{"enter_seconds":1,"exit_seconds":1}}]}
        """);
        var playback = new MotionRulePlayback();
        assertEquals(0, playback.sample(rules.extras(), 0).get(0).weight());
        assertEquals(.5, playback.sample(rules.extras(), .5).get(0).weight(), 1e-6);
        assertEquals(.5, playback.sample(List.of(), .5).get(0).weight(), 1e-6);
        assertEquals(.375, playback.sample(List.of(), .75).get(0).weight(), 1e-6);
        assertEquals(.375, playback.sample(rules.extras(), .75).get(0).weight(), 1e-6);
        assertTrue(playback.sample(List.of(), 3).size() > 0);
        assertTrue(playback.sample(List.of(), 4).isEmpty());
    }
}
