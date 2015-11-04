/*
 * Copyright (C) 2015 Collaboratory
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.dockstore.common;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.dbutils.handlers.KeyedHandler;
import org.apache.commons.io.FileUtils;

/**
 *
 * @author xliu
 */
public class CommonTestUtilities {
    public static final String DUMMY_ADMIN_PASSWORD = "08932ab0c9ae39a880905666902f8659633ae0232e94ba9f3d2094cb928397e7";

    public static HierarchicalINIConfiguration parseConfig(String path) {
        try {
            return new HierarchicalINIConfiguration(path);
        } catch (ConfigurationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static class TestingPostgres extends BasicPostgreSQL {

        public TestingPostgres(HierarchicalINIConfiguration config) {
            super(config);
        }

        @Override
        public void clearDatabase() {
            super.clearDatabase();
            this.runInsertStatement("insert into enduser(id, isAdmin, username) VALUES (1,true,'admin@admin.com');", new KeyedHandler<>(
                    "id"));
            this.runInsertStatement("insert into token(id, content, tokensource, userid, username) VALUES (1, '" + DUMMY_ADMIN_PASSWORD
                    + "', 'dockstore', 1, 'admin@admin.com');", new KeyedHandler<>("id"));
        }
    }

    /**
     * Clears database state and known queues for testing.
     *
     * @throws IOException
     * @throws java.util.concurrent.TimeoutException
     */
    public static void clearState() throws IOException, TimeoutException {
        File configFile = FileUtils.getFile("src", "test", "resources", "config");
        HierarchicalINIConfiguration parseConfig = Utilities.parseConfig(configFile.getAbsolutePath());
        TestingPostgres postgres = new TestingPostgres(parseConfig);
        postgres.clearDatabase();
    }
}
