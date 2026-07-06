/*
 *    Copyright (c) 2026, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.inmemorydb.queries;

import java.sql.SQLException;

import io.supertokens.inmemorydb.Start;
import io.supertokens.inmemorydb.config.Config;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.multitenancy.AppIdentifier;

import static io.supertokens.inmemorydb.QueryExecutorTemplate.execute;

/**
 * The read-only half of the backfill contract, mirroring the postgresql plugin's
 * MigrationBackfillQueries so that core-level rules built on these counts (e.g. the
 * migration_mode → MIGRATED transition guard) can be tested against the in-memory
 * storage. The batch backfill itself is not mirrored: the in-memory DB never holds
 * pre-12.0 legacy data, so there is nothing to backfill (see Start.backfillUsersBatch).
 */
public class MigrationBackfillQueries {

    /**
     * Returns the count of users with time_joined = 0, indicating they need backfilling.
     */
    public static int getBackfillPendingUsersCount(Start start, AppIdentifier appIdentifier)
            throws SQLException, StorageQueryException {
        String QUERY = "SELECT COUNT(*) FROM " + Config.getConfig(start).getAppIdToUserIdTable()
                + " WHERE app_id = ? AND time_joined = 0";

        return execute(start, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
        }, result -> {
            if (result.next()) {
                return result.getInt(1);
            }
            return 0;
        });
    }

    /**
     * Verifies backfill completeness by counting users missing from reservation tables.
     */
    public static int verifyBackfillCompleteness(Start start, AppIdentifier appIdentifier)
            throws SQLException, StorageQueryException {
        String QUERY = "SELECT COUNT(*) FROM " + Config.getConfig(start).getAppIdToUserIdTable() + " a"
                + " WHERE a.app_id = ? AND NOT EXISTS ("
                + "   SELECT 1 FROM " + Config.getConfig(start).getRecipeUserAccountInfosTable() + " rai"
                + "   WHERE rai.app_id = a.app_id AND rai.recipe_user_id = a.user_id"
                + " )";

        return execute(start, QUERY, pst -> {
            pst.setString(1, appIdentifier.getAppId());
        }, result -> {
            if (result.next()) {
                return result.getInt(1);
            }
            return 0;
        });
    }
}
