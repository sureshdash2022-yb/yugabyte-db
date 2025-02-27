//--------------------------------------------------------------------------------------------------
// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//--------------------------------------------------------------------------------------------------

#pragma once

#include "libpq-fe.h" // NOLINT

#include "yb/util/net/net_util.h"

#include "yb/yql/pgwrapper/libpq_utils.h"

namespace yb {
namespace pgwrapper {

// <major, minor>
typedef std::pair<int, int> Version;

// Uses pgwrapper::PGConn to perform YSQL cluster upgrade.
class YsqlUpgradeHelper {
 public:
  YsqlUpgradeHelper(const HostPort& ysql_proxy_addr,
                    uint64_t ysql_auth_key,
                    uint32_t heartbeat_interval_ms,
                    bool use_single_connection);

  // Main actor method, perform the full upgrade process.
  Status Upgrade();

 private:
  class DatabaseEntry;
  class ReusableConnectionDatabaseEntry;
  class SingletonConnectionDatabaseEntry;

  // Analyze the on-disk list of available migrations to determine latest_version_
  // and fill in migration_filenames_map_.
  Status AnalyzeMigrationFiles();

  Result<std::unique_ptr<DatabaseEntry>> MakeDatabaseEntry(std::string database_name);

  // Migrate a given database to the next version, updating it in the given database entry.
  Status MigrateOnce(DatabaseEntry* db_entry);

  const HostPort ysql_proxy_addr_;

  const uint64_t ysql_auth_key_;

  const uint32_t heartbeat_interval_ms_;

  // Perform an upgrade unsing just one connection.
  // This is much slower but does not incur overhead for each database.
  const bool use_single_connection_;

  // Whether pg_yb_catalog_version migration has been applied, and we don't need to wait for
  // heartbeats anymore.
  // Since it's a shared relation, it only needs to be applied once.
  bool catalog_version_migration_applied_ = false;

  // Latest version for which migration script is present.
  // 0.0 indicates that AnalyzeMigrationFiles() hasn't been called yet.
  Version latest_version_{0, 0};

  // Map from version to migration filename.
  std::map<Version, std::string> migration_filenames_map_{};

  std::string migrations_dir_{""};
};

}  // namespace pgwrapper
}  // namespace yb
