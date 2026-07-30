package io.github.lexaquila.lyradb.desktop.transfer;

import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 连接导入的纯内存冲突计划器。
 *
 * <p>任何与现有连接或导入文件内其他项冲突的配置均默认跳过，只有显式决策才能
 * 重命名或覆盖。</p>
 */
public final class ConnectionImportPlanner {

    public enum Action {
        IMPORT("导入"),
        SKIP("跳过"),
        RENAME("重命名"),
        OVERWRITE("覆盖");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum ConflictKind {
        NONE,
        EXISTING,
        FILE_DUPLICATE
    }

    public record PreviewItem(int index,
                              DesktopConnection incoming,
                              ConflictKind conflictKind,
                              DesktopConnection existingTarget,
                              String conflictDescription,
                              Action defaultAction,
                              String suggestedName) {
        public PreviewItem {
            incoming = incoming.copy();
            existingTarget = existingTarget == null ? null : existingTarget.copy();
        }

        @Override
        public DesktopConnection incoming() {
            return incoming.copy();
        }

        @Override
        public DesktopConnection existingTarget() {
            return existingTarget == null ? null : existingTarget.copy();
        }
    }

    public record Decision(int index, Action action, String targetName) {
    }

    public record Resolution(List<DesktopConnection> toSave,
                             Set<String> overwrittenIds,
                             int importedCount,
                             int renamedCount,
                             int overwrittenCount,
                             int skippedCount) {
        public Resolution {
            toSave = toSave.stream().map(DesktopConnection::copy).toList();
            overwrittenIds = Set.copyOf(overwrittenIds);
        }

        @Override
        public List<DesktopConnection> toSave() {
            return toSave.stream().map(DesktopConnection::copy).toList();
        }
    }

    public List<PreviewItem> preview(List<DesktopConnection> existing,
            List<DesktopConnection> incoming) {
        List<DesktopConnection> safeExisting = copy(existing);
        List<DesktopConnection> safeIncoming = copy(incoming);
        Map<String, DesktopConnection> existingById = new HashMap<>();
        Map<String, DesktopConnection> existingByName = new HashMap<>();
        Set<String> occupiedNames = new HashSet<>();
        for (DesktopConnection value : safeExisting) {
            existingById.put(value.getId(), value);
            existingByName.put(normalizeName(value.getName()), value);
            occupiedNames.add(normalizeName(value.getName()));
        }

        Set<String> seenIncomingIds = new HashSet<>();
        Set<String> seenIncomingNames = new HashSet<>();
        List<PreviewItem> result = new ArrayList<>();
        for (int index = 0; index < safeIncoming.size(); index++) {
            DesktopConnection value = safeIncoming.get(index);
            DesktopConnection target = existingById.get(value.getId());
            if (target == null) {
                target = existingByName.get(normalizeName(value.getName()));
            }
            String incomingId = value.getId();
            boolean duplicateId = incomingId != null
                    && !incomingId.isBlank()
                    && !seenIncomingIds.add(incomingId);
            boolean duplicateName = !seenIncomingNames.add(
                    normalizeName(value.getName()));
            boolean duplicateInFile = duplicateId || duplicateName;
            ConflictKind kind;
            String conflict;
            if (target != null) {
                kind = ConflictKind.EXISTING;
                conflict = "与现有连接“" + target.getName() + "”冲突";
            } else if (duplicateInFile) {
                kind = ConflictKind.FILE_DUPLICATE;
                conflict = "导入文件内存在重复名称或 ID";
            } else {
                kind = ConflictKind.NONE;
                conflict = "无冲突";
            }
            Action defaultAction = kind == ConflictKind.NONE
                    ? Action.IMPORT : Action.SKIP;
            String suggested = uniqueName(value.getName(), occupiedNames);
            occupiedNames.add(normalizeName(
                    kind == ConflictKind.NONE ? value.getName() : suggested));
            result.add(new PreviewItem(index, value, kind, target, conflict,
                    defaultAction, suggested));
        }
        return List.copyOf(result);
    }

    public Resolution resolve(List<DesktopConnection> existing,
            List<PreviewItem> preview,
            List<Decision> decisions) {
        Map<Integer, Decision> byIndex = new LinkedHashMap<>();
        if (decisions != null) {
            for (Decision decision : decisions) {
                if (decision == null || byIndex.put(decision.index(), decision) != null) {
                    throw new IllegalArgumentException("导入决策索引重复或为空");
                }
            }
        }

        Set<String> occupiedIds = new HashSet<>();
        Set<String> occupiedNames = new HashSet<>();
        for (DesktopConnection value : copy(existing)) {
            occupiedIds.add(value.getId());
            occupiedNames.add(normalizeName(value.getName()));
        }

        List<DesktopConnection> toSave = new ArrayList<>();
        Set<String> overwrittenIds = new HashSet<>();
        int imported = 0;
        int renamed = 0;
        int overwritten = 0;
        int skipped = 0;
        for (PreviewItem item : preview) {
            Decision decision = byIndex.getOrDefault(item.index(),
                    new Decision(item.index(), item.defaultAction(),
                            item.suggestedName()));
            Action action = decision.action() == null
                    ? item.defaultAction() : decision.action();
            if (action == Action.SKIP) {
                skipped++;
                continue;
            }
            DesktopConnection value = item.incoming();
            switch (action) {
                case IMPORT -> {
                    if (item.conflictKind() != ConflictKind.NONE) {
                        throw new IllegalArgumentException(
                                "冲突连接不能直接导入，请选择跳过、重命名或覆盖");
                    }
                    if (value.getId() == null || value.getId().isBlank()) {
                        value.setId(newConnectionId(occupiedIds));
                    }
                    ensureAvailable(value, occupiedIds, occupiedNames);
                    occupiedIds.add(value.getId());
                    occupiedNames.add(normalizeName(value.getName()));
                    imported++;
                }
                case RENAME -> {
                    String targetName = decision.targetName() == null
                            ? "" : decision.targetName().trim();
                    if (targetName.isBlank()) {
                        throw new IllegalArgumentException("重命名后的连接名称不能为空");
                    }
                    String normalized = normalizeName(targetName);
                    if (occupiedNames.contains(normalized)) {
                        throw new IllegalArgumentException(
                                "连接名称“" + targetName + "”仍然冲突");
                    }
                    value.setId(newConnectionId(occupiedIds));
                    value.setName(targetName);
                    occupiedIds.add(value.getId());
                    occupiedNames.add(normalized);
                    renamed++;
                }
                case OVERWRITE -> {
                    if (item.conflictKind() != ConflictKind.EXISTING
                            || item.existingTarget() == null) {
                        throw new IllegalArgumentException(
                                "只有与现有连接冲突的配置才能覆盖");
                    }
                    DesktopConnection target = item.existingTarget();
                    if (!overwrittenIds.add(target.getId())) {
                        throw new IllegalArgumentException(
                                "同一个现有连接不能被覆盖多次");
                    }
                    occupiedIds.remove(target.getId());
                    occupiedNames.remove(normalizeName(target.getName()));
                    value.setId(target.getId());
                    String normalized = normalizeName(value.getName());
                    if (occupiedNames.contains(normalized)) {
                        throw new IllegalArgumentException(
                                "覆盖后的连接名称与其他连接冲突");
                    }
                    occupiedIds.add(value.getId());
                    occupiedNames.add(normalized);
                    overwritten++;
                }
                default -> throw new IllegalStateException("未知导入动作");
            }
            toSave.add(value);
        }
        return new Resolution(toSave, overwrittenIds,
                imported, renamed, overwritten, skipped);
    }

    private static void ensureAvailable(DesktopConnection value,
            Set<String> occupiedIds, Set<String> occupiedNames) {
        if (occupiedIds.contains(value.getId())
                || occupiedNames.contains(normalizeName(value.getName()))) {
            throw new IllegalArgumentException(
                    "连接“" + value.getName() + "”仍然存在冲突");
        }
    }

    private static String newConnectionId(Set<String> occupiedIds) {
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (occupiedIds.contains(id));
        return id;
    }

    private static String uniqueName(String original, Set<String> occupiedNames) {
        String base = original == null || original.isBlank() ? "导入连接" : original.trim();
        String candidate = base + "（导入）";
        int suffix = 2;
        while (occupiedNames.contains(normalizeName(candidate))) {
            candidate = base + "（导入 " + suffix++ + "）";
        }
        return candidate;
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<DesktopConnection> copy(List<DesktopConnection> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(DesktopConnection::copy).toList();
    }
}
