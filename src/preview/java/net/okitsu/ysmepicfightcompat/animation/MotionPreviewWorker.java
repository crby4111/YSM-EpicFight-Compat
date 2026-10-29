package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.*;

import net.minecraft.resources.ResourceLocation;
import net.okitsu.ysmepicfightcompat.assets.*;
import net.okitsu.ysmepicfightcompat.mesh.*;
import net.okitsu.ysmepicfightcompat.diagnostics.RecordedPoseData;

import yesman.epicfight.api.asset.JsonAssetLoader;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.model.armature.HumanoidArmature;

import java.io.*;
import java.net.URI;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Local stdin/stdout worker. Pure CPU, controlled facts, no Minecraft client or entity. */
public final class MotionPreviewWorker {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final MotionRuleSet EF = MotionRuleSet.parse(new JsonObject());
    private static final String PREFIX = "assets/epicfight/animmodels/animations/";
    private static final int FPS = 20;
    private static final int MAX_FRAMES = 161;
    // Keep aligned with the native Boolean ctrl.* query cases in
    // EntityAnimationEnvironment. Only movement facts are active in this preview;
    // other known game flags are intentionally zero, while unknown names are reported.
    private static final Set<String> NATIVE_ACTION_CONTROL_FLAGS = Set.of(
            "ctrl.death", "ctrl.riptide", "ctrl.sleep", "ctrl.swim", "ctrl.climb",
            "ctrl.climbing", "ctrl.ladder_up", "ctrl.ladder_stillness", "ctrl.ladder_down",
            "ctrl.fly", "ctrl.elytra_fly", "ctrl.swim_stand", "ctrl.attacked", "ctrl.jump",
            "ctrl.sneak", "ctrl.sneaking", "ctrl.run", "ctrl.walk", "ctrl.idle");
    // This mirrors SnapshotExpressionEnvironment.invoke's numeric switch. Actual math remains
    // delegated to that evaluator; the set only distinguishes implemented names from fallback 0.
    private static final Set<String> PREVIEW_NUMERIC_FUNCTIONS = Set.of(
            "math.floor", "math.round", "math.ceil", "math.trunc", "math.abs", "math.exp",
            "math.ln", "math.sqrt", "math.pow", "math.mod", "math.sin", "math.cos",
            "math.asin", "math.acos", "math.atan", "math.atan2", "math.min", "math.max",
            "math.clamp", "math.lerp", "math.lerprotate", "math.lerprotatee", "math.hermite",
            "math.hermite_blend", "math.min_angle", "ysm.perlin_noise");
    private final Map<String, ModelBundle> models = new LinkedHashMap<>();
    private final Map<String, String> epicAssets = new TreeMap<>();

    public MotionPreviewWorker() throws Exception {
        URI location =
                JsonAssetLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        try (var zip = new ZipFile(Path.of(location).toFile())) {
            zip.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.startsWith(PREFIX + "biped/") && name.endsWith(".json"))
                    .filter(name -> !name.contains("/data/") && !name.contains("/pov/"))
                    .forEach(
                            name ->
                                    epicAssets.put(
                                            "epicfight:"
                                                    + name.substring(
                                                            PREFIX.length(), name.length() - 5),
                                            name));
        }
    }

    private JsonObject asset(String path) throws IOException {
        try (var stream = JsonAssetLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IOException("EF 动作资源不存在：" + path);
            return JsonParser.parseString(
                            new String(stream.readNBytes(8 * 1024 * 1024), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private HumanoidArmature armature() throws IOException {
        return new JsonAssetLoader(
                        asset("assets/epicfight/animmodels/entity/biped.json"),
                        new ResourceLocation("epicfight", "entity/biped"))
                .loadArmature(HumanoidArmature::new);
    }

    private ModelBundle model(JsonObject request) {
        Path root = Path.of(request.get("root").getAsString()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("模型目录不存在");
        String id = request.get("model").getAsString();
        String key = root + "\0" + id;
        if (!models.containsKey(key)) {
            if (models.size() >= 2) models.remove(models.keySet().iterator().next());
            models.put(key, PreviewModels.load(root, id));
        }
        return models.get(key);
    }

    private Object inspect(JsonObject request) throws Exception {
        ModelBundle model = model(request);
        SkinMeshCompiler.Result mesh = SkinMeshCompiler.compile(model);
        if (mesh == null) throw new IllegalArgumentException("模型没有可显示的几何面");
        if (mesh.arrays().get("positions").length > 1_500_000)
            throw new IllegalArgumentException("模型网格超出预览上限");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", model.modelId());
        result.put("positions", mesh.arrays().get("positions"));
        result.put("normals", mesh.arrays().get("normals"));
        result.put("uvs", mesh.arrays().get("uvs"));
        Number[] influences = mesh.arrays().get("vindices");
        int[] owners = new int[influences.length / 2];
        for (int i = 0; i < owners.length; i++) owners[i] = influences[i * 2].intValue();
        result.put("owners", owners);
        var indices = new ArrayList<Integer>();
        mesh.parts().values().forEach(part -> part.forEach(v -> indices.add(v.position)));
        mesh.glowParts().values().forEach(part -> part.forEach(v -> indices.add(v.position)));
        result.put("triangles", indices);
        var bones = new ArrayList<Object>();
        for (var entry : mesh.auxiliaryBones().entries())
            bones.add(
                    Map.of(
                            "name",
                            entry.bone().name(),
                            "pose",
                            entry.poseIndex(),
                            "parent",
                            entry.parentAuxiliaryIndex(),
                            "pivot",
                            new float[] {
                                entry.bone().pivotX() * model.widthScale(),
                                entry.bone().pivotY() * model.heightScale(),
                                entry.bone().pivotZ() * model.widthScale()
                            }));
        result.put("bones", bones);
        result.put("sourceBones", model.geometry().bones().size());
        result.put("mappedBones", mesh.auxiliaryBones().entries().size());
        result.put("poseCount", mesh.auxiliaryBones().totalPoseCount());
        result.put("faces", mesh.faceCount());
        result.put("clips", model.animations().keySet());
        result.put("controllers", controllerNames(model));
        var textures = new LinkedHashMap<String, String>();
        for (var entry : model.textures().entrySet()) {
            byte[] bytes = entry.getValue();
            if (bytes.length > 16 * 1024 * 1024) continue;
            String mime =
                    bytes.length > 8 && bytes[0] == (byte) 137 && bytes[1] == 80
                            ? "image/png"
                            : bytes.length > 2 && bytes[0] == (byte) 255 && bytes[1] == (byte) 216
                                    ? "image/jpeg"
                                    : "";
            if (!mime.isEmpty())
                textures.put(
                        entry.getKey(),
                        "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
            if (textures.size() >= 16) break;
        }
        result.put("textures", textures);
        result.put("defaultTexture", model.defaultTexture());
        return result;
    }

    private Object sample(JsonObject request) throws Exception {
        ModelBundle model = model(request);
        String epicId = request.get("epic").getAsString();
        String assetPath = epicAssets.get(epicId);
        if (assetPath == null) throw new IllegalArgumentException("请从列表选择 EF 动作");
        SkinMeshCompiler.Result mesh = SkinMeshCompiler.compile(model);
        if (mesh == null) throw new IllegalArgumentException("模型网格为空");
        HumanoidArmature arm = armature();
        var epic =
                new JsonAssetLoader(asset(assetPath), new ResourceLocation(epicId))
                        .loadAnimationClip(arm);
        double duration = epic.getClipTime();
        if (!Double.isFinite(duration) || duration <= 0)
            throw new IllegalArgumentException("动作时长无效");
        double seconds =
                request.has("seconds")
                        ? request.get("seconds").getAsDouble()
                        : Math.min(4, Math.max(1, duration));
        if (!Double.isFinite(seconds) || seconds < .05 || seconds > 8)
            throw new IllegalArgumentException("预览时长应为 0.05～8 秒");
        int fps =
                Math.min(
                        FPS,
                        Math.max(
                                5,
                                (int)
                                        (28L
                                                * 1024
                                                * 1024
                                                / (seconds
                                                        * 2
                                                        * mesh.auxiliaryBones().totalPoseCount()
                                                        * 64))));
        int count = Math.min(MAX_FRAMES, (int) Math.ceil(seconds * fps) + 1);
        MotionRuleSet rules =
                request.has("rules") ? MotionRuleSet.parse(request.getAsJsonObject("rules")) : EF;
        if (!rules.enabled())
            throw new IllegalArgumentException("预览当前规则时，请先将 enabled 设为 true；不会改动游戏配置");
        String mode = request.has("mode") ? request.get("mode").getAsString() : "rules";
        if (mode.equals("clip") || mode.equals("controller")) {
            boolean controller = mode.equals("controller");
            String selected = request.get(controller ? "controller" : "clip").getAsString();
            if (!(controller ? controllerNames(model).contains(selected) : model.animations().containsKey(selected)))
                throw new IllegalArgumentException("模型片段或控制器不存在：" + selected);
            var source = new JsonObject();
            source.addProperty("id", "model:" + selected);
            if (controller) source.addProperty("kind", "controller");
            var override = new JsonObject();
            override.addProperty("id", controller ? "preview-controller" : "preview-clip");
            override.add("source", source);
            var list = new JsonArray();
            list.add(override);
            var root = new JsonObject();
            root.add("overrides", list);
            rules = MotionRuleSet.parse(root);
        } else if (mode.equals("ef") || mode.equals("appearance")) rules = EF;
        else if (!mode.equals("rules")) throw new IllegalArgumentException("未知预览模式");
        var facts = request.has("facts") ? request.getAsJsonObject("facts") : new JsonObject();
        var values = new LinkedHashMap<String, String>();
        values.put("model", model.modelId());
        values.put("view", "third_person");
        values.put("movement", text(facts, "movement", "idle"));
        values.put("item", text(facts, "item", "minecraft:air"));
        for (String name :
                List.of(
                        "attacking",
                        "hurt",
                        "battle_mode",
                        "using_item",
                        "sneaking",
                        "sprinting",
                        "on_ground",
                        "in_water"))
            values.put(
                    name,
                    facts.has(name)
                            ? facts.get(name).getAsString()
                            : name.equals("battle_mode") || name.equals("on_ground")
                                    ? "true"
                                    : "false");
        var fixed = new MotionRuleSet.Facts(values, Set.of(epicId));
        var external = new LinkedHashMap<String, AnimationClip>();
        JsonObject bundled =
                asset("assets/ysm_epicfight_compat/motion_animations/example.json")
                        .getAsJsonObject("animations");
        bundled.entrySet()
                .forEach(
                        entry ->
                                external.put(
                                        "ysm_epicfight_compat:" + entry.getKey(),
                                        BedrockAnimationParser.parse(
                                                "ysm_epicfight_compat:" + entry.getKey(),
                                                entry.getValue().getAsJsonObject())));
        if (request.has("externalAnimations")) {
            JsonObject supplied = request.getAsJsonObject("externalAnimations");
            if (supplied.size() > 256) throw new IllegalArgumentException("额外动作不能超过 256 个");
            supplied.entrySet()
                    .forEach(
                            entry -> {
                                if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                                        || entry.getKey().startsWith("model:"))
                                    throw new IllegalArgumentException("额外动作 ID 应为 命名空间:片段名");
                                external.put(
                                        entry.getKey(),
                                        BedrockAnimationParser.parse(
                                                entry.getKey(),
                                                entry.getValue().getAsJsonObject()));
                            });
        }
        boolean compareAppearanceControllers = mode.equals("appearance");
        Session left =
                        new Session(model, mesh, external, !compareAppearanceControllers),
                right = new Session(model, mesh, external, true);
        int poses = mesh.auxiliaryBones().totalPoseCount();
        ByteBuffer matrices =
                ByteBuffer.allocate(count * 2 * poses * 16 * 4).order(ByteOrder.LITTLE_ENDIAN);
        var diagnoses = new ArrayList<String>();
        for (int frame = 0; frame < count; frame++) {
            double time = Math.min(seconds, frame / (double) fps);
            double clipTime = Math.min(time, Math.max(0, duration - 0.00001));
            arm.setPose(epic.getPoseInTime((float) clipTime));
            var sample =
                    new MotionRuleFacts.Sample(
                            fixed, Map.of(epicId, new MotionRuleFacts.Clock(clipTime, duration)));
            OpenMatrix4f[] a = left.sample(time, sample, EF, facts, arm);
            OpenMatrix4f[] b = right.sample(time, sample, rules, facts, arm);
            put(matrices, a);
            put(matrices, b);
            diagnoses.add(right.state.diagnostic);
        }
        var response = new LinkedHashMap<String, Object>(Map.of(
                "schema",
                1,
                "fps",
                fps,
                "frames",
                count,
                "poses",
                poses,
                "seconds",
                seconds,
                "duration",
                duration,
                "matrices",
                Base64.getEncoder().encodeToString(matrices.array()),
                "diagnostics",
                diagnoses,
                "unhandled",
                right.environment.unhandled,
                "scope",
                "离线受控姿态 · 共用当前兼容计算 · 未运行游戏"));
        response.put("baselineHiddenBones", left.hiddenBoneFrames);
        response.put("ruleHiddenBones", right.hiddenBoneFrames);
        return response;
    }

    private static void put(ByteBuffer buffer, OpenMatrix4f[] matrices) {
        for (var m : matrices)
            for (float v :
                    new float[] {
                        m.m00, m.m01, m.m02, m.m03, m.m10, m.m11, m.m12, m.m13, m.m20, m.m21, m.m22,
                        m.m23, m.m30, m.m31, m.m32, m.m33
                    }) {
                if (!Float.isFinite(v)) throw new IllegalStateException("姿态产生非有限值");
                buffer.putFloat(v);
            }
    }

    private static String text(JsonObject o, String key, String fallback) {
        return o.has(key) ? o.get(key).getAsString() : fallback;
    }

    private static boolean isAppearanceController(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        int separator = Math.max(normalized.lastIndexOf('.'), normalized.lastIndexOf(':'));
        String path = normalized.substring(separator + 1);
        return path.startsWith("parallel") || path.startsWith("pre_parallel");
    }

    private static Set<String> controllerNames(ModelBundle model) {
        var names = new TreeSet<>(model.animationControllers().keySet());
        for (String name : new MolangScriptRuntime(model.functions(), Map.of()).controllers())
            if (ParallelAnimationProgram.supportsScriptController(name)) names.add(name);
        return names;
    }

    private static final class Session {
        final ParallelAnimationProgram program;
        final ParallelAnimationProgram.UserMotionState state =
                new ParallelAnimationProgram.UserMotionState();
        final AuxiliaryPoseMatrices composer;
        final AuxiliaryBoneLayout layout;
        final Environment environment;
        final List<List<String>> hiddenBoneFrames = new ArrayList<>();
        double lastNow;

        Session(
                ModelBundle model,
                SkinMeshCompiler.Result mesh,
                Map<String, AnimationClip> external) {
            this(model, mesh, external, true);
        }

        Session(
                ModelBundle model,
                SkinMeshCompiler.Result mesh,
                Map<String, AnimationClip> external,
                boolean includeAppearanceControllers) {
            layout = mesh.auxiliaryBones();
            var animations = new LinkedHashMap<String, AnimationClip>(model.animations());
            external.forEach(animations::putIfAbsent);
            var controllers = new LinkedHashMap<>(model.animationControllers());
            if (!includeAppearanceControllers)
                controllers.keySet().removeIf(MotionPreviewWorker::isAppearanceController);
            program =
                    new ParallelAnimationProgram(
                            model.modelId(),
                            model.geometry(),
                            animations,
                            controllers,
                            model.functions(),
                            layout,
                            model.widthScale(),
                            model.heightScale());
            composer = new AuxiliaryPoseMatrices(layout);
            environment = new Environment(model, animations);
        }

        OpenMatrix4f[] sample(
                double now,
                MotionRuleFacts.Sample facts,
                MotionRuleSet rules,
                JsonObject controls,
                Armature arm) {
            environment.update(now, Math.max(0, now - lastNow), facts.facts().values(), controls);
            lastNow = now;
            environment.scripts.frame(now, environment);
            var frame = program.sampleUserRulesAt(now, false, rules, facts, environment, state);
            hiddenBoneFrames.add(frame.hiddenBones().stream().sorted().toList());
            var complete =
                    composer.compose(
                            arm,
                            arm.getPoseMatrices(),
                            frame.parallelDeltas(),
                            frame.wholeModelDeltas(),
                            frame.heldItemDeltas(),
                            frame.replaceEpicFightPose(),
                            frame.replaceEpicFightAnchors(),
                            frame.suppressParallelDeltas(),
                            frame.heldItemAnchorJoints(),
                            frame.fullBodyBlendSource(),
                            frame.fullBodyBlendWeight(),
                            null,
                            frame.authoredDeltas());
            if (complete == null) throw new IllegalStateException("无法合成 EF 骨架");
            state.lastSkin = new OpenMatrix4f[layout.entries().size()];
            for (var entry : layout.entries())
                state.lastSkin[entry.auxiliaryIndex()] =
                        new OpenMatrix4f(complete[entry.poseIndex()]);
            environment.bones =
                    DisplayedBoneQueries.capture(
                            layout, complete, environment.bones, frame.hiddenBones());
            for (var entry : layout.entries())
                if (frame.hiddenBones().contains(entry.bone().name())) {
                    var m = complete[entry.poseIndex()];
                    m.m00 = m.m01 = m.m02 = m.m10 = m.m11 = m.m12 = m.m20 = m.m21 = m.m22 = 0;
                }
            return complete;
        }
    }

    /**
     * Explicit offline inputs. Unknown game functions are reported, never described as supported.
     */
    private static final class Environment
            implements MolangScriptRuntime.Host, ClipTimeEnvironment {
        final Map<Integer, Object> variables = new HashMap<>();
        final Map<String, Double> queries = new HashMap<>();
        final Set<String> unhandled = new TreeSet<>();
        final AuxiliaryPhysicsRuntime physics = new AuxiliaryPhysicsRuntime();
        final SnapshotExpressionEnvironment math =
                SnapshotExpressionEnvironment.capture(this, Set.of(), Set.of());
        final MolangScriptRuntime scripts;
        BoneQuerySnapshot bones = BoneQuerySnapshot.EMPTY;
        double[] velocity = new double[3], position = new double[3];

        Environment(ModelBundle model, Map<String, AnimationClip> animations) {
            var clips = new LinkedHashMap<String, MolangScriptRuntime.Clip>();
            animations.forEach(
                    (name, clip) ->
                            clips.put(
                                    name,
                                    new MolangScriptRuntime.Clip(
                                            clip.duration(), clip.playback())));
            scripts = new MolangScriptRuntime(model.functions(), clips);
        }

        public MolangScriptRuntime scripts() {
            return scripts;
        }

        public boolean externalOutputsEnabled() {
            return false;
        }

        public void clipTime(double time) {
            queries.put("query.anim_time", time);
        }

        public double readVariable(int slot) {
            return ExpressionEngine.number(readVariableValue(slot));
        }

        public Object readVariableValue(int slot) {
            return variables.getOrDefault(slot, 0d);
        }

        public boolean hasVariable(int slot) {
            return variables.containsKey(slot);
        }

        public void writeVariable(int slot, double value) {
            variables.put(slot, Double.isFinite(value) ? value : 0d);
        }

        public void writeVariableValue(int slot, Object value) {
            variables.put(slot, value);
        }

        public double readQuery(int slot) {
            return ExpressionEngine.number(readQueryValue(slot));
        }

        public Object readQueryValue(int slot) {
            String name = ExpressionEngine.slotName(slot);
            Object value = scripts.read(name, this);
            if (value != MolangScriptRuntime.UNHANDLED) return value;
            // Controlled preview has no active ParCool animator. This is a typed
            // empty string, not numeric zero: model controllers compare it to ''.
            if (name.equals("ctrl.parcool_state")) return "";
            if (!queries.containsKey(name) && !NATIVE_ACTION_CONTROL_FLAGS.contains(name))
                unhandled.add(name);
            return queries.getOrDefault(name, 0d);
        }

        public double invoke(String name, double[] args) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.equals("query.position") || normalized.equals("query.position_delta")) {
                int axis = args.length == 0 ? 0 : (int) args[0];
                if (axis < 0 || axis > 2) axis = 0;
                return normalized.equals("query.position") ? position[axis] : velocity[axis] / 20;
            }
            if (!PREVIEW_NUMERIC_FUNCTIONS.contains(normalized)) {
                unhandled.add(normalized);
                return 0;
            }
            return math.invoke(normalized, args);
        }

        public double invokeWithText(String name, String[] args) {
            unhandled.add(name);
            return 0;
        }

        public double invokeWithMixedArguments(String name, String[] text, double[] args) {
            if (name.equals("ysm.second_order") && args.length >= 2)
                return physics.secondOrder(
                        text[0],
                        args[1],
                        args.length > 2 ? args[2] : 1,
                        args.length > 3 ? args[3] : 1,
                        args.length > 4 ? args[4] : 1);
            if (name.equals("ysm.first_order") && args.length >= 2)
                return physics.firstOrder(text[0], args[1], args.length > 2 ? args[2] : 1);
            return invokeWithText(name, text);
        }

        public Object invokeValue(String name, Object[] args) {
            Object result = scripts.invoke(name, args, this);
            if (result != MolangScriptRuntime.UNHANDLED) return result;
            if (Set.of("ysm.bone_rot", "ysm.bone_pos", "ysm.bone_scale", "ysm.bone_pivot_abs")
                    .contains(name))
                return bones.query(name, args.length > 0 ? String.valueOf(args[0]) : "");
            return MolangScriptRuntime.Host.super.invokeValue(name, args);
        }

        void update(double now, double dt, Map<String, String> facts, JsonObject controls) {
            physics.update(dt);
            queries.clear();
            queries.put("query.life_time", now);
            queries.put("query.anim_time", now);
            queries.put("query.delta_time", dt);
            queries.put("ysm.delta_time", dt);
            queries.put("query.health", 20d);
            queries.put("query.max_health", 20d);
            queries.put("query.is_alive", 1d);
            queries.put("math.pi", Math.PI);
            queries.put("math.e", Math.E);
            queries.put("query.is_first_person", 0d);
            queries.put("query.is_in_ui", 0d);
            double pitch = controls.has("pitch") ? controls.get("pitch").getAsDouble() : 0;
            double yaw = controls.has("yaw") ? controls.get("yaw").getAsDouble() : 0;
            double turnRate = input(controls, "yaw_speed", 0, 7200);
            double bodyYaw = input(controls, "body_yaw", 0, 360000) + turnRate * now;
            queries.put("query.yaw_speed", turnRate);
            queries.put("query.body_y_rotation", ((bodyYaw + 180) % 360 + 360) % 360 - 180);
            queries.put("query.body_x_rotation", -pitch);
            for (String key : List.of("ysm.head_pitch", "query.head_y_rotation"))
                queries.put(key, pitch);
            queries.put("query.eye_target_x_rotation", -pitch);
            for (String key :
                    List.of("ysm.head_yaw", "query.head_x_rotation", "query.eye_target_y_rotation"))
                queries.put(key, yaw);
            // YSM's relative head yaw has the opposite sign to the world eye yaw.
            double eyeYaw = bodyYaw - yaw;
            queries.put("query.eye_target_y_rotation", ((eyeYaw + 180) % 360 + 360) % 360 - 180);
            for (var mapping :
                    Map.of(
                                    "on_ground",
                                    "query.is_on_ground",
                                    "in_water",
                                    "query.is_in_water",
                                    "sneaking",
                                    "query.is_sneaking",
                                    "sprinting",
                                    "query.is_sprinting")
                            .entrySet())
                queries.put(
                        mapping.getValue(),
                        Boolean.parseBoolean(facts.get(mapping.getKey())) ? 1d : 0d);
            String movement = facts.get("movement");
            String main =
                    switch (movement) {
                        case "sneak_idle" -> "sneaking";
                        case "sneak_move" -> "sneak";
                        case "water_idle" -> "swim_stand";
                        case "creative_flight" -> "fly";
                        case "elytra_flight" -> "elytra_fly";
                        case "crawl_idle" -> "climbing";
                        case "crawl_move" -> "climb";
                        case "ladder_idle" -> "ladder_stillness";
                        default -> movement;
                    };
            queries.put("ctrl." + main, 1d);
            boolean moving =
                    Set.of("walk", "run", "swim", "sneak_move", "crawl_move").contains(movement);
            queries.put("query.ground_speed", moving ? movement.equals("run") ? 5d : 2d : 0d);
            queries.put("query.modified_move_speed", moving ? 1d : 0d);
            queries.put("ysm.input_vertical", moving ? 1d : 0d);
            queries.put("ysm.input_horizontal", 0d);
            queries.put(
                    "query.is_in_water_or_rain",
                    Boolean.parseBoolean(facts.get("in_water")) ? 1d : 0d);
            queries.put("query.is_swimming", movement.equals("swim") ? 1d : 0d);
            queries.put("ysm.eye_in_water", Boolean.parseBoolean(facts.get("in_water")) ? 1d : 0d);
            queries.put("query.vertical_speed", movement.equals("jump") ? .2d : 0d);
            queries.put("query.is_moving", moving ? 1d : 0d);
            double speed = moving ? movement.equals("run") ? 5 : 2 : 0;
            velocity[0] = input(controls, "velocity_x", 0, 1000);
            velocity[1] = input(controls, "velocity_y", movement.equals("jump") ? .2 : 0, 1000);
            velocity[2] = input(controls, "velocity_z", speed, 1000);
            for (int axis = 0; axis < 3; axis++)
                position[axis] = input(controls, "position_" + "xyz".charAt(axis), 0, 30_000_000)
                        + velocity[axis] * now;
            double horizontal = Math.hypot(velocity[0], velocity[2]);
            queries.put("query.ground_speed", horizontal);
            queries.put("query.vertical_speed", velocity[1]);
            queries.put("query.modified_distance_moved", horizontal * now);
            queries.put("query.walk_distance", horizontal * now);
            queries.put("query.is_moving", horizontal > .001 ? 1d : 0d);
        }

        private static double input(JsonObject controls, String key, double fallback, double maximum) {
            double value = controls.has(key) ? controls.get(key).getAsDouble() : fallback;
            if (!Double.isFinite(value) || Math.abs(value) > maximum)
                throw new IllegalArgumentException("预览输入无效：" + key);
            return value;
        }
    }

    private Object recorded(JsonObject request) throws Exception {
        var report = request.getAsJsonObject("report");
        if (report.get("schema").getAsInt() != 1) throw new IllegalArgumentException("记录格式不支持");
        var draw = report.getAsJsonObject("draw");
        if (!draw.has("model")) throw new IllegalArgumentException("此记录未取得兼容模型姿态，不能重建");
        if (!request.get("model").getAsString().equals(draw.get("model").getAsString()))
            throw new IllegalArgumentException("请先选择记录对应的模型");
        ModelBundle model = model(request);
        var mesh = SkinMeshCompiler.compile(model);
        if (mesh == null) throw new IllegalArgumentException("模型网格为空");
        var layout = mesh.auxiliaryBones();
        var recordedBones = draw.getAsJsonArray("bones");
        if (recordedBones.size() != layout.entries().size())
            throw new IllegalArgumentException("记录与当前模型的骨架数量不同");
        for (int index = 0; index < recordedBones.size(); index++) {
            var bone = recordedBones.get(index).getAsJsonObject();
            var expected = layout.entries().get(index);
            if (!expected.bone().name().equals(bone.get("name").getAsString())
                    || expected.poseIndex() != bone.get("pose").getAsInt()
                    || expected.parentAuxiliaryIndex() != bone.get("parent").getAsInt()
                    || expected.anchorJoint() != bone.get("anchor").getAsInt())
                throw new IllegalArgumentException("记录与当前模型的骨架不匹配：" + expected.bone().name());
            var bind = bone.getAsJsonArray("bind_world_unscaled");
            float[] currentBind = expected.bindWorld().get(new float[16]);
            if (bind.size() != 16) throw new IllegalArgumentException("记录绑定矩阵不完整");
            for (int component = 0; component < 16; component++) {
                double value = bind.get(component).getAsDouble();
                if (!Double.isFinite(value) || Math.abs(value - currentBind[component]) > 1e-5)
                    throw new IllegalArgumentException("记录与当前模型的绑定姿态不同：" + expected.bone().name());
            }
        }
        var scale = draw.getAsJsonArray("model_scale");
        if (scale.size() != 3 || !Double.isFinite(scale.get(0).getAsDouble())
                || !Double.isFinite(scale.get(1).getAsDouble()) || !Double.isFinite(scale.get(2).getAsDouble())
                || Math.abs(scale.get(0).getAsDouble() - model.widthScale()) > 1e-6
                || Math.abs(scale.get(1).getAsDouble() - model.heightScale()) > 1e-6
                || Math.abs(scale.get(2).getAsDouble() - model.widthScale()) > 1e-6)
            throw new IllegalArgumentException("记录与当前模型缩放不同");
        int count = layout.totalPoseCount();
        var input = RecordedPoseData.readMatrices(draw.getAsJsonObject("epic_input"),
                HumanoidRig.EPIC_JOINT_COUNT, AuxiliaryBoneLayout.MAX_TOTAL_POSES);
        var skin = RecordedPoseData.readMatrices(draw.getAsJsonObject("final_skin"), count, count);
        var hidden = draw.has("hidden_draw_bones") ? draw.getAsJsonArray("hidden_draw_bones")
                : draw.getAsJsonArray("hidden_bones");
        var hiddenNames = new HashSet<String>();
        if (hidden != null) for (var name : hidden) hiddenNames.add(name.getAsString());
        for (var entry : layout.entries()) if (hiddenNames.contains(entry.bone().name())) {
            var matrix = skin[entry.poseIndex()];
            matrix.m00 = matrix.m01 = matrix.m02 = matrix.m10 = matrix.m11 = matrix.m12
                    = matrix.m20 = matrix.m21 = matrix.m22 = 0;
        }
        var baseline = new AuxiliaryPoseMatrices(layout).compose(armature(), input, null, null,
                null, false, null, null, null);
        if (baseline == null) throw new IllegalArgumentException("记录的 EF 输入无法重建");
        var buffer = ByteBuffer.allocate(count * 2 * 64).order(ByteOrder.LITTLE_ENDIAN);
        put(buffer, baseline);
        put(buffer, skin);
        String diagnostic = "游戏记录帧 · " + draw.get("model").getAsString()
                + " · tick=" + draw.get("tick") + " · " + draw.get("rule_diagnostic")
                + "；右侧直接显示记录的蒙皮结果，未重新推进动画或物理";
        var response = new LinkedHashMap<String, Object>();
        response.put("schema", 1);
        response.put("fps", 20);
        response.put("frames", 1);
        response.put("poses", count);
        response.put("seconds", 0);
        response.put("duration", 0);
        response.put("matrices", Base64.getEncoder().encodeToString(buffer.array()));
        response.put("diagnostics", List.of(diagnostic));
        response.put("unhandled", List.of("记录帧不包含游戏摄像机、装备、光影及完整物理历史；仅核对骨架与缩放，几何和贴图仍来自当前模型库"));
        response.put("recorded", true);
        return response;
    }

    private Object request(JsonObject request) throws Exception {
        return switch (request.get("command").getAsString()) {
            case "catalogue" -> {
                models.clear();
                yield Map.of(
                        "models",
                        PreviewModels.discover(Path.of(request.get("root").getAsString())),
                        "epic",
                        epicAssets.keySet());
            }
            case "inspect" -> inspect(request);
            case "sample" -> sample(request);
            case "recorded" -> recorded(request);
            case "reset" -> {
                models.clear();
                yield Map.of("ok", true);
            }
            default -> throw new IllegalArgumentException("未知命令");
        };
    }

    public static void main(String[] args) throws Exception {
        MotionPreviewWorker worker = new MotionPreviewWorker();
        try (var reader =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    if (line.length() > 17 * 1024 * 1024) throw new IllegalArgumentException("预览请求过大");
                    var request = JsonParser.parseString(line).getAsJsonObject();
                    if (!"recorded".equals(request.get("command").getAsString()) && line.length() > 512 * 1024)
                        throw new IllegalArgumentException("预览请求过大");
                    Object result = worker.request(request);
                    System.out.println(
                            "PREVIEW " + GSON.toJson(Map.of("ok", true, "result", result)));
                } catch (Exception | LinkageError ex) {
                    System.out.println(
                            "PREVIEW " + GSON.toJson(Map.of("ok", false, "error", ex.toString())));
                }
                System.out.flush();
            }
        }
    }
}
