package com.zhh.nl2sql.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "metadata")
public class MetaProperties {

    private SyncConfig sync = new SyncConfig();
    private List<DataSourceConfig> datasources = new ArrayList<>();
    private List<TableConfig> tables = new ArrayList<>();
    private List<MetricConfig> metrics = new ArrayList<>();

    @Data
    public static class SyncConfig {
        private boolean enabled = true;
        private String cron = "0 0 2 * * ?";
        private String incrementalCron = "0 */30 * * * ?";
        private int batchSize = 50;
    }

    @Data
    public static class DataSourceConfig {
        private String id;
        private String name;
        private String url;
        private String username;
        private String password;
        private String driver;
        private String layer;
    }

    @Data
    public static class TableConfig {
        private String datasourceId;
        private String tableName;
        private String displayName;
        private String description;
        private List<String> tags = new ArrayList<>();
        private boolean enabled = true;
        private List<ColumnConfig> columns = new ArrayList<>();
    }

    @Data
    public static class ColumnConfig {
        private String name;
        private String businessName;
        private String description;
        private Map<String, String> enumValues = new LinkedHashMap<>();
        private String queryConstraint;
        private boolean sensitive = false;
    }

    @Data
    public static class MetricConfig {
        private String name;
        private List<String> aliases = new ArrayList<>();
        private String description;
        private String formula;
        private List<String> relatedTables = new ArrayList<>();
        private List<String> constraints = new ArrayList<>();
        private String exampleSql;
    }
}