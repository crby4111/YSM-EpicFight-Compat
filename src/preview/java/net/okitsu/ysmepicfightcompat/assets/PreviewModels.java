package net.okitsu.ysmepicfightcompat.assets;

import java.nio.file.Path;
import java.util.Map;

/** Explicit user-selected source root, with exactly the production loader and its bounds. */
public final class PreviewModels {
    private PreviewModels() {}

    public static Map<String, Boolean> discover(Path root) {
        return LocalModelRepository.discover(root);
    }

    public static ModelBundle load(Path root, String id) {
        ModelBundle model = LocalModelRepository.load(root, id);
        if (model == null)
            throw new IllegalArgumentException(
                    "模型无法读取。请选择含 builtin/custom/auth 的 yes_steve_model 目录；受保护的包需要官方运行环境。模型：" + id);
        return model;
    }
}
