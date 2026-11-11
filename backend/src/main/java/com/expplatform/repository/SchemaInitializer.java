package com.expplatform.repository;

import com.expplatform.schema.Schema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Creates tables from the schema registry and offers a fast dataset reset. */
@Component
public class SchemaInitializer {

    private final Db db;
    private final boolean dropOnStart;

    public SchemaInitializer(Db db, @Value("${app.schema.drop-on-start:false}") boolean dropOnStart) {
        this.db = db;
        this.dropOnStart = dropOnStart;
    }

    public void initialize() {
        if (dropOnStart) {
            List<String> all = new ArrayList<>(Schema.OPERATIONAL_TABLES);
            List<String> data = new ArrayList<>(Schema.allTables().stream().map(Schema.Table::name).toList());
            Collections.reverse(data);
            all.addAll(data);
            withoutFkChecks(all.stream().map(t -> "DROP TABLE IF EXISTS " + t).toList());
        }
        for (Schema.Table t : Schema.allTables()) db.jdbc.execute(Schema.ddl(t));
        for (String ddl : Schema.OPERATIONAL_DDL) db.jdbc.execute(ddl);
    }

    /** Empties every dataset table (operational history tables are kept). */
    public void truncateDataset() {
        List<String> stmts = new ArrayList<>();
        for (Schema.Table t : Schema.allTables()) stmts.add("TRUNCATE TABLE " + t.name());
        withoutFkChecks(stmts);
    }

    private void withoutFkChecks(List<String> statements) {
        db.jdbc.execute((ConnectionCallback<Void>) con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                try {
                    for (String s : statements) st.execute(s);
                } finally {
                    st.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
            }
            return null;
        });
    }
}
