package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 为管理员授权表单读取元数据名称，不读取表内容。工作空间校验由控制器完成。 */
@Service
public class AdminGrantScopeService {
    private static final int MAX_NAMESPACES = 200;
    private static final int MAX_TABLES = 2_500;
    private static final Set<String> TABLE_TYPES = Set.of("TABLE", "VIEW", "COLLECTION");
    private static final Set<String> CONTAINER_TYPES = Set.of("DATABASE", "SCHEMA", "PROJECT");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private final DataSourceService dataSourceService;

    public AdminGrantScopeService(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    public ScopeOptions options(DataSource source) throws Exception {
        if ("REDIS".equalsIgnoreCase(source.getDbType())) {
            return new ScopeOptions(List.of(), List.of(), false);
        }
        Map<String, NamespaceOption> namespaces = new LinkedHashMap<>();
        Set<String> tables = new LinkedHashSet<>();
        boolean truncated = false;
        ConnectionService.ActiveConnection active =
                dataSourceService.resolveActiveConnection(source.getId());
        try (ConnectionService.ActiveConnection.Lease ignored = active.acquire()) {
            List<TreeNode> roots = safeNodes(active.driver.getTreeNodes(active.connection, null));
            String rootTableNamespace = rootTableNamespace(source);
            for (TreeNode root : roots) {
                if (isTable(root)) {
                    addRootTable(rootTableNamespace, root, namespaces, tables);
                } else if (isContainer(root) && valid(root.getName())) {
                    String rootPath = path(root);
                    List<TreeNode> children = safeNodes(active.driver.getTreeNodes(
                            active.connection, rootPath));
                    List<TreeNode> nestedNamespaces = children.stream()
                            .filter(AdminGrantScopeService::isContainer)
                            .filter(node -> valid(node.getName())).toList();
                    if (nestedNamespaces.isEmpty()) {
                        addNamespace(root.getName(), root.getName(), namespaces);
                        addTables(root.getName(), children, tables);
                    } else {
                        for (TreeNode nested : nestedNamespaces) {
                            String prefix = root.getName() + "." + nested.getName();
                            addNamespace(nested.getName(), prefix, namespaces);
                            addTables(prefix, safeNodes(active.driver.getTreeNodes(
                                    active.connection, path(nested))), tables);
                            if (namespaces.size() > MAX_NAMESPACES
                                    || tables.size() > MAX_TABLES) break;
                        }
                    }
                }
                if (namespaces.size() > MAX_NAMESPACES
                        || tables.size() > MAX_TABLES) {
                    truncated = true;
                    break;
                }
            }
        }
        List<NamespaceOption> visibleNamespaces = new ArrayList<>(namespaces.values());
        List<String> visibleTables = new ArrayList<>(tables);
        if (visibleNamespaces.size() > MAX_NAMESPACES) {
            visibleNamespaces = visibleNamespaces.subList(0, MAX_NAMESPACES);
            truncated = true;
        }
        if (visibleTables.size() > MAX_TABLES) {
            visibleTables = visibleTables.subList(0, MAX_TABLES);
            truncated = true;
        }
        return new ScopeOptions(List.copyOf(visibleNamespaces),
                List.copyOf(visibleTables), truncated);
    }

    private String rootTableNamespace(DataSource source) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) dataSourceService
                .getMasked(source.getId()).get("params");
        for (String key : List.of("schema", "project", "database")) {
            Object value = params == null ? null : params.get(key);
            if (value != null && valid(value.toString())) return value.toString();
        }
        return "SQLITE".equalsIgnoreCase(source.getDbType()) ? "main" : null;
    }

    private static void addRootTable(String namespace, TreeNode node,
                                     Map<String, NamespaceOption> namespaces,
                                     Set<String> tables) {
        if (namespace == null) return;
        addNamespace(namespace, namespace, namespaces);
        if (valid(node.getName())) tables.add(namespace + "." + node.getName());
    }

    private static void addNamespace(String value, String prefix,
                                     Map<String, NamespaceOption> namespaces) {
        namespaces.putIfAbsent(prefix.toLowerCase(Locale.ROOT),
                new NamespaceOption(value, prefix, prefix));
    }

    private static void addTables(String prefix, List<TreeNode> nodes, Set<String> tables) {
        for (TreeNode node : nodes) {
            if (isTable(node) && valid(node.getName())) {
                tables.add(prefix + "." + node.getName());
            }
        }
    }

    private static String path(TreeNode node) {
        return node.getPath() == null || node.getPath().isBlank()
                ? node.getName() : node.getPath();
    }

    private static boolean valid(String name) {
        return name != null && IDENTIFIER.matcher(name).matches();
    }

    private static boolean isTable(TreeNode node) {
        return node != null && TABLE_TYPES.contains(upper(node.getType()));
    }

    private static boolean isContainer(TreeNode node) {
        return node != null && CONTAINER_TYPES.contains(upper(node.getType()));
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static List<TreeNode> safeNodes(List<TreeNode> nodes) {
        return nodes == null ? List.of() : nodes;
    }

    public record NamespaceOption(String value, String label, String tablePrefix) { }
    public record ScopeOptions(List<NamespaceOption> namespaces,
                               List<String> tables, boolean truncated) { }
}
