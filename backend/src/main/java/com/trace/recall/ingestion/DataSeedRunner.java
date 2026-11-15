package com.trace.recall.ingestion;

import com.trace.recall.common.Json;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动后播种：先召回域演示数据，再替代目录域验收样例数据。
 */
@Component
@Order(1)
public class DataSeedRunner implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final IngestionSeeder ingestionSeeder;
    private final com.trace.recall.substitution.SubstitutionSeeder substitutionSeeder;

    public DataSeedRunner(JdbcTemplate jdbc, IngestionSeeder ingestionSeeder,
                          com.trace.recall.substitution.SubstitutionSeeder substitutionSeeder) {
        this.jdbc = jdbc;
        this.ingestionSeeder = ingestionSeeder;
        this.substitutionSeeder = substitutionSeeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        ingestionSeeder.seed();
        Integer subCnt = jdbc.queryForObject("SELECT count(*) FROM catalog_document", Integer.class);
        if (subCnt == null || subCnt == 0) {
            substitutionSeeder.seed();
        }
    }
}
