// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.alter;

import org.apache.doris.alter.AlterJob.JobState;
import org.apache.doris.analysis.AddRollupClause;
import org.apache.doris.analysis.AlterClause;
import org.apache.doris.analysis.CancelAlterTableStmt;
import org.apache.doris.analysis.CancelStmt;
import org.apache.doris.analysis.DropRollupClause;
import org.apache.doris.catalog.AggregateType;
import org.apache.doris.catalog.Catalog;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.Database;
import org.apache.doris.catalog.KeysType;
import org.apache.doris.catalog.MaterializedIndex;
import org.apache.doris.catalog.MaterializedIndex.IndexState;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.catalog.OlapTable.OlapTableState;
import org.apache.doris.catalog.Partition;
import org.apache.doris.catalog.Replica;
import org.apache.doris.catalog.Replica.ReplicaState;
import org.apache.doris.catalog.Table;
import org.apache.doris.catalog.Tablet;
import org.apache.doris.catalog.TabletInvertedIndex;
import org.apache.doris.catalog.TabletMeta;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.common.util.ListComparator;
import org.apache.doris.common.util.Util;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.persist.DropInfo;
import org.apache.doris.persist.EditLog;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.thrift.TStorageMedium;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * RollupHandler is responsible for ADD/DROP rollup.
 */
public class RollupHandler extends AlterHandler {
    private static final Logger LOG = LogManager.getLogger(RollupHandler.class);

    public RollupHandler() {
        super("rollup");
    }

    /*
     * Handle the Add Rollup request.
     * 3 main steps:
     * 1. Validate the request.
     * 2. Create RollupJob with rollup index
     *      All replicas of the rollup index will be created in meta and added to TabletInvertedIndex
     * 3. Set table's state to ROLLUP.
     */
    private void processAddRollup(AddRollupClause alterClause, Database db, OlapTable olapTable)
            throws DdlException {
        
        // table is under rollup or has a finishing alter job
        if (olapTable.getState() == OlapTableState.ROLLUP || this.hasUnfinishedAlterJob(olapTable.getId())) {
            throw new DdlException("Table[" + olapTable.getName() + "]'s is under ROLLUP");
        }
        // up to here, table's state can only be NORMAL
        Preconditions.checkState(olapTable.getState() == OlapTableState.NORMAL, olapTable.getState().name());

        String rollupIndexName = alterClause.getRollupName();
        String baseIndexName = alterClause.getBaseRollupName();
        List<String> rollupColumnNames = alterClause.getColumnNames();

        LOG.info("process add rollup[{}] based on [{}]", rollupIndexName, baseIndexName);

        // 1. check if rollup index already exists
        if (olapTable.hasMaterializedIndex(rollupIndexName)) {
            throw new DdlException("Rollup index[" + rollupIndexName + "] already exists");
        }

        // 2. get base index schema
        if (baseIndexName == null) {
            // use table name as base table name
            baseIndexName = olapTable.getName();
        }
        Long baseIndexId = olapTable.getIndexIdByName(baseIndexName);
        if (baseIndexId == null) {
            throw new DdlException("Base index[" + baseIndexName + "] does not exist");
        }

        // check state
        for (Partition partition : olapTable.getPartitions()) {
            MaterializedIndex baseIndex = partition.getIndex(baseIndexId);
            // up to here. index's state should only be NORMAL
            Preconditions.checkState(baseIndex.getState() == IndexState.NORMAL, baseIndex.getState().name());
        }

        // 3. check if rollup columns are valid
        // a. all columns should exist in base rollup schema
        // b. value after key
        // c. if rollup contains REPLACE column, all keys on base index should be included.
        List<Column> rollupSchema = new ArrayList<Column>();
        // check (a)(b)
        boolean meetValue = false;
        boolean hasKey = false;
        boolean meetReplaceValue = false;
        KeysType keysType = olapTable.getKeysType();
        if (KeysType.UNIQUE_KEYS == keysType || KeysType.AGG_KEYS == keysType) {
            int keysNumOfRollup = 0;
            for (String columnName : rollupColumnNames) {
                Column oneColumn = olapTable.getColumn(columnName);
                if (oneColumn == null) {
                    throw new DdlException("Column[" + columnName + "] does not exist");
                }
                if (oneColumn.isKey() && meetValue) {
                    throw new DdlException("Invalid column order. value should be after key");
                }
                if (oneColumn.isKey()) {
                    keysNumOfRollup += 1;
                    hasKey = true;
                } else {
                    meetValue = true;
                    if (oneColumn.getAggregationType() == AggregateType.REPLACE) {
                        meetReplaceValue = true;
                    }
                }
                rollupSchema.add(oneColumn);
            }

            if (!hasKey) {
                throw new DdlException("No key column is found");
            }
            
            if (KeysType.UNIQUE_KEYS == keysType || meetReplaceValue) {
                // rollup of unique key table or rollup with REPLACE value
                // should have all keys of base table
                if (keysNumOfRollup !=  olapTable.getKeysNum()) {
                    if (KeysType.UNIQUE_KEYS == keysType) {
                        throw new DdlException("Rollup should contains all unique keys in basetable");
                    } else {
                        throw new DdlException("Rollup should contains all keys if there is a REPLACE value");
                    }
                }
            }
        } else if (KeysType.DUP_KEYS == keysType) {
            /*
             * eg.
             * Base Table's schema is (k1,k2,k3,k4,k5) dup key (k1,k2,k3).
             * The following rollup is allowed:
             * 1. (k1) dup key (k1)
             * 2. (k2,k3) dup key (k2)
             * 3. (k1,k2,k3) dup key (k1,k2)
             * 
             * The following rollup is forbidden:
             * 1. (k1) dup key (k2)
             * 2. (k2,k3) dup key (k3,k2)
             * 3. (k1,k2,k3) dup key (k2,k3)
             */
            if (alterClause.getDupKeys() == null || alterClause.getDupKeys().isEmpty()) {
                // user does not specify duplicate key for rollup,
                // use base table's duplicate key.
                // so we should check if rollup columns contains all base table's duplicate key.
                List<Column> baseIdxCols = olapTable.getSchemaByIndexId(baseIndexId);
                Set<String> baseIdxKeyColNames = Sets.newHashSet();
                for (Column baseCol : baseIdxCols) {
                    if (baseCol.isKey()) {
                        baseIdxKeyColNames.add(baseCol.getName());
                    } else {
                        break;
                    }
                }

                boolean found = false;
                for (String baseIdxKeyColName : baseIdxKeyColNames) {
                    found = false;
                    for (String rollupColName : rollupColumnNames) {
                        if (rollupColName.equalsIgnoreCase(baseIdxKeyColName)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        throw new DdlException("Rollup should contains all base table's duplicate keys if "
                                + "no duplicate key is specified: " + baseIdxKeyColName);
                    }
                }

                // check (a)(b)
                for (String columnName : rollupColumnNames) {
                    Column oneColumn = olapTable.getColumn(columnName);
                    if (oneColumn == null) {
                        throw new DdlException("Column[" + columnName + "] does not exist");
                    }
                    if (oneColumn.isKey() && meetValue) {
                        throw new DdlException("Invalid column order. key should before all values: " + columnName);
                    }
                    if (oneColumn.isKey()) {
                        hasKey = true;
                    } else {
                        meetValue = true;
                    }
                    rollupSchema.add(oneColumn);
                }

                if (!hasKey) {
                    throw new DdlException("No key column is found");
                }
            } else {
                // user specify the duplicate keys for rollup index
                List<String> dupKeys = alterClause.getDupKeys();
                if (dupKeys.size() > rollupColumnNames.size()) {
                    throw new DdlException("Num of duplicate keys should less than or equal to num of rollup columns.");
                }

                for (int i = 0; i < rollupColumnNames.size(); i++) {
                    String rollupColName = rollupColumnNames.get(i);
                    boolean isKey = false;
                    if (i < dupKeys.size()) {
                        String dupKeyName = dupKeys.get(i);
                        if (!rollupColName.equalsIgnoreCase(dupKeyName)) {
                            throw new DdlException("Duplicate keys should be the prefix of rollup columns");
                        }
                        isKey = true;
                    }

                    if (olapTable.getColumn(rollupColName) == null) {
                        throw new DdlException("Column[" + rollupColName + "] does not exist");
                    }

                    if (isKey && meetValue) {
                        throw new DdlException("Invalid column order. key should before all values: " + rollupColName);
                    }

                    Column oneColumn = new Column(olapTable.getColumn(rollupColName));
                    if (isKey) {
                        hasKey = true;
                        oneColumn.setIsKey(true);
                        oneColumn.setAggregationType(null, false);
                    } else {
                        meetValue = true;
                        oneColumn.setIsKey(false);
                        oneColumn.setAggregationType(AggregateType.NONE, true);
                    }
                    rollupSchema.add(oneColumn);
                }
            }
        }

        // assign rollup index's key type, same as base index's
        KeysType rollupKeysType = keysType;
        

        // get rollup schema hash
        int rollupSchemaHash = Util.schemaHash(0 /* init schema version */, rollupSchema, olapTable.getCopiedBfColumns(),
                                               olapTable.getBfFpp());

        // get short key column count
        Map<String, String> properties = alterClause.getProperties();
        short rollupShortKeyColumnCount = Catalog.calcShortKeyColumnCount(rollupSchema, properties);
        
        // get timeout
        long timeoutMs = alterClause.getTimeoutSecond() * 1000;

        // 4. create rollup job
        long dbId = db.getId();
        long tableId = olapTable.getId();
        int baseSchemaHash = olapTable.getSchemaHashByIndexId(baseIndexId);

        Catalog catalog = Catalog.getCurrentCatalog();
        long jobId = catalog.getNextId();
        long rollupIndexId = catalog.getNextId();
        
        RollupJobV2 rollupJob = new RollupJobV2(jobId, dbId, tableId, olapTable.getName(), timeoutMs,
                baseIndexId, rollupIndexId, baseIndexName, rollupIndexName,
                rollupSchema, baseSchemaHash, rollupSchemaHash,
                rollupKeysType, rollupShortKeyColumnCount);

        /*
         * create all rollup indexes. and set state.
         * After setting, Tables' state will be ROLLUP
         */
        for (Partition partition : olapTable.getPartitions()) {
            long partitionId = partition.getId();
            TStorageMedium medium = olapTable.getPartitionInfo().getDataProperty(partitionId).getStorageMedium();
            // index state is SHADOW
            MaterializedIndex rollupIndex = new MaterializedIndex(rollupIndexId, IndexState.SHADOW);
            MaterializedIndex baseIndex = partition.getIndex(baseIndexId);
            TabletMeta rollupTabletMeta = new TabletMeta(dbId, tableId, partitionId, rollupIndexId, rollupSchemaHash, medium);
            for (Tablet baseTablet : baseIndex.getTablets()) {
                long baseTabletId = baseTablet.getId();
                long rollupTabletId = catalog.getNextId();

                Tablet newTablet = new Tablet(rollupTabletId);
                rollupIndex.addTablet(newTablet, rollupTabletMeta);

                rollupJob.addTabletIdMap(partitionId, rollupTabletId, baseTabletId);
                List<Replica> baseReplicas = baseTablet.getReplicas();

                for (Replica baseReplica : baseReplicas) {
                    long rollupReplicaId = catalog.getNextId();
                    long backendId = baseReplica.getBackendId();
                    if (baseReplica.getState() == ReplicaState.CLONE 
                            || baseReplica.getState() == ReplicaState.DECOMMISSION
                            || baseReplica.getLastFailedVersion() > 0) {
                        // just skip it.
                        continue;
                    }
                    Preconditions.checkState(baseReplica.getState() == ReplicaState.NORMAL);
                    // replica's init state is ALTER, so that tablet report process will ignore its report
                    Replica rollupReplica = new Replica(rollupReplicaId, backendId, ReplicaState.ALTER,
                            Partition.PARTITION_INIT_VERSION, Partition.PARTITION_INIT_VERSION_HASH,
                            rollupSchemaHash);
                    newTablet.addReplica(rollupReplica);
                } // end for baseReplica
            } // end for baseTablets

            rollupJob.addRollupIndex(partitionId, rollupIndex);

            LOG.debug("create rollup index {} based on index {} in partition {}",
                    rollupIndexId, baseIndexId, partitionId);
        } // end for partitions

        // update table state
        olapTable.setState(OlapTableState.ROLLUP);

        addAlterJobV2(rollupJob);

        // log rollup operation
        catalog.getEditLog().logAlterJob(rollupJob);
        LOG.info("finished to create rollup job: {}", rollupJob.getJobId());
    }

    public void processDropRollup(DropRollupClause alterClause, Database db, OlapTable olapTable)
            throws DdlException {
        // make sure we got db write lock here.
        // up to here, table's state can only be NORMAL.
        Preconditions.checkState(olapTable.getState() == OlapTableState.NORMAL, olapTable.getState().name());

        String rollupIndexName = alterClause.getRollupName();
        if (rollupIndexName.equals(olapTable.getName())) {
            throw new DdlException("Cannot drop base index by using DROP ROLLUP.");
        }

        long dbId = db.getId();
        long tableId = olapTable.getId();
        if (!olapTable.hasMaterializedIndex(rollupIndexName)) {
            throw new DdlException("Rollup index[" + rollupIndexName + "] does not exist in table["
                    + olapTable.getName() + "]");
        }
        
        long rollupIndexId = olapTable.getIndexIdByName(rollupIndexName);
        int rollupSchemaHash = olapTable.getSchemaHashByIndexId(rollupIndexId);
        Preconditions.checkState(rollupSchemaHash != -1);

        // drop rollup for each partition.
        // also remove tablets from inverted index.
        TabletInvertedIndex invertedIndex = Catalog.getCurrentInvertedIndex();
        for (Partition partition : olapTable.getPartitions()) {
            MaterializedIndex rollupIndex = partition.getIndex(rollupIndexId);
            Preconditions.checkNotNull(rollupIndex);

            // delete rollup index
            partition.deleteRollupIndex(rollupIndexId);

            // remove tablets from inverted index
            for (Tablet tablet : rollupIndex.getTablets()) {
                long tabletId = tablet.getId();
                invertedIndex.deleteTablet(tabletId);
            }
        }

        olapTable.deleteIndexInfo(rollupIndexName);

        // log drop rollup operation
        EditLog editLog = Catalog.getInstance().getEditLog();
        DropInfo dropInfo = new DropInfo(dbId, tableId, rollupIndexId);
        editLog.logDropRollup(dropInfo);
        LOG.info("finished drop rollup index[{}] in table[{}]", rollupIndexName, olapTable.getName());
    }

    public void replayDropRollup(DropInfo dropInfo, Catalog catalog) {
        Database db = catalog.getDb(dropInfo.getDbId());
        db.writeLock();
        try {
            long tableId = dropInfo.getTableId();
            long rollupIndexId = dropInfo.getIndexId();

            TabletInvertedIndex invertedIndex = Catalog.getCurrentInvertedIndex();
            OlapTable olapTable = (OlapTable) db.getTable(tableId);
            for (Partition partition : olapTable.getPartitions()) {
                MaterializedIndex rollupIndex = partition.deleteRollupIndex(rollupIndexId);

                if (!Catalog.isCheckpointThread()) {
                    // remove from inverted index
                    for (Tablet tablet : rollupIndex.getTablets()) {
                        invertedIndex.deleteTablet(tablet.getId());
                    }
                }
            }

            String rollupIndexName = olapTable.getIndexNameById(rollupIndexId);
            olapTable.deleteIndexInfo(rollupIndexName);
        } finally {
            db.writeUnlock();
        }
        LOG.info("replay drop rollup {}", dropInfo.getIndexId());
    }

    @Override
    protected void runOneCycle() {
        super.runOneCycle();
        runOldAlterJob();
        runAlterJobV2();
    }

    private void runAlterJobV2() {
        Iterator<Map.Entry<Long, AlterJobV2>> iter = alterJobsV2.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Long, AlterJobV2> entry = iter.next();
            AlterJobV2 alterJob = entry.getValue();
            if (alterJob.isDone()) {
                continue;
            }
            alterJob.run();
        }
    }

    @Deprecated
    private void runOldAlterJob() {
        List<AlterJob> cancelledJobs = Lists.newArrayList();
        List<AlterJob> finishedJobs = Lists.newArrayList();

        for (AlterJob alterJob : alterJobs.values()) {
            RollupJob rollupJob = (RollupJob) alterJob;
            if (rollupJob.getState() != JobState.FINISHING 
                    && rollupJob.getState() != JobState.FINISHED 
                    && rollupJob.getState() != JobState.CANCELLED) {
                // cancel the old alter table job
                cancelledJobs.add(rollupJob);
                continue;
            }
            
            if (rollupJob.getTransactionId() < 0) {
                // it means this is an old type job and current version is real time load version
                // then kill this job
                cancelledJobs.add(rollupJob);
                continue;
            }
            JobState state = rollupJob.getState();
            switch (state) {
                case PENDING: {
                    // if rollup job's status is PENDING, we need to send tasks.
                    if (!rollupJob.sendTasks()) {
                        cancelledJobs.add(rollupJob);
                        LOG.warn("sending rollup job[" + rollupJob.getTableId() + "] tasks failed. cancel it.");
                    }
                    break;
                }
                case RUNNING: {
                    if (rollupJob.isTimeout()) {
                        cancelledJobs.add(rollupJob);
                    } else {
                        int res = rollupJob.tryFinishJob();
                        if (res == -1) {
                            // cancel rollup
                            cancelledJobs.add(rollupJob);
                            LOG.warn("cancel rollup[{}] cause bad rollup job[{}]",
                                     ((RollupJob) rollupJob).getRollupIndexName(), rollupJob.getTableId());
                        }
                    }
                    break;
                }
                case FINISHING: {
                    // check previous load job finished
                    if (rollupJob.isPreviousLoadFinished()) {
                        // if all previous load job finished, then send clear alter tasks to all related be
                        LOG.info("previous txn finished, try to send clear txn task");
                        int res = rollupJob.checkOrResendClearTasks();
                        if (res != 0) {
                            LOG.info("send clear txn task return {}", res);
                            if (res == -1) {
                                LOG.warn("rollup job is in finishing state, but could not finished, "
                                        + "just finish it, maybe a fatal error {}", rollupJob);
                            }
                            finishedJobs.add(rollupJob);
                        }
                    } else {
                        LOG.info("previous load jobs are not finished. can not finish rollup job: {}",
                                rollupJob.getTableId());
                    }
                    break;
                }
                case FINISHED: {
                    break;
                }
                case CANCELLED: {
                    // the alter job could be cancelled in 3 ways
                    // 1. the table or db is dropped
                    // 2. user cancels the job
                    // 3. the job meets errors when running
                    // for the previous 2 scenarios, user will call jobdone to finish the job and set its state to cancelled
                    // so that there exists alter job whose state is cancelled
                    // for the third scenario, the thread will add to cancelled job list and will be dealt by call jobdone
                    // Preconditions.checkState(false);
                    break;
                }
                default:
                    Preconditions.checkState(false);
                    break;
            }
        } // end for jobs

        // handle cancelled rollup jobs
        for (AlterJob rollupJob : cancelledJobs) {
            Database db = Catalog.getInstance().getDb(rollupJob.getDbId());
            if (db == null) {
                cancelInternal(rollupJob, null, null);
                continue;
            }

            db.writeLock();
            try {
                OlapTable olapTable = (OlapTable) db.getTable(rollupJob.getTableId());
                rollupJob.cancel(olapTable, "cancelled");
            } finally {
                db.writeUnlock();
            }
            jobDone(rollupJob);
        }

        // handle finished rollup jobs
        for (AlterJob alterJob : finishedJobs) {
            alterJob.setState(JobState.FINISHED);
            // remove from alterJobs.
            // has to remove here, because the job maybe finished and it still in alter job list,
            // then user could submit schema change task, and auto load to two table flag will be set false.
            // then schema change job will be failed.
            alterJob.finishJob();
            jobDone(alterJob);
            Catalog.getInstance().getEditLog().logFinishRollup((RollupJob) alterJob);
        }
    }

    @Override
    public List<List<Comparable>> getAlterJobInfosByDb(Database db) {
        List<List<Comparable>> rollupJobInfos = new LinkedList<List<Comparable>>();

        getOldAlterJobInfos(db, rollupJobInfos);
        getAlterJobV2Infos(db, rollupJobInfos);

        // sort by
        // "JobId", "TableName", "CreateTime", "FinishedTime", "BaseIndexName", "RollupIndexName"
        ListComparator<List<Comparable>> comparator = new ListComparator<List<Comparable>>(0, 1, 2, 3, 4, 5);
        Collections.sort(rollupJobInfos, comparator);

        return rollupJobInfos;
    }

    private void getAlterJobV2Infos(Database db, List<List<Comparable>> rollupJobInfos) {
        ConnectContext ctx = ConnectContext.get();
        for (AlterJobV2 alterJob : alterJobsV2.values()) {
            if (alterJob.getDbId() != db.getId()) {
                continue;
            }
            if (ctx != null) {
                if (!Catalog.getCurrentCatalog().getAuth().checkTblPriv(ctx, db.getFullName(), alterJob.getTableName(), PrivPredicate.ALTER)) {
                    continue;
                }
            }
            alterJob.getInfo(rollupJobInfos);
        }
    }

    @Deprecated
    private void getOldAlterJobInfos(Database db, List<List<Comparable>> rollupJobInfos) {
        List<AlterJob> jobs = Lists.newArrayList();
        // lock to perform atomically
        lock();
        try {
            for (AlterJob alterJob : this.alterJobs.values()) {
                if (alterJob.getDbId() == db.getId()) {
                    jobs.add(alterJob);
                }
            }

            for (AlterJob alterJob : this.finishedOrCancelledAlterJobs) {
                if (alterJob.getDbId() == db.getId()) {
                    jobs.add(alterJob);
                }
            }
        } finally {
            unlock();
        }
        
        db.readLock();
        try {
            for (AlterJob selectedJob : jobs) {
                OlapTable olapTable = (OlapTable) db.getTable(selectedJob.getTableId());
                if (olapTable == null) {
                    continue;
                }

                selectedJob.getJobInfo(rollupJobInfos, olapTable);
            }
        } finally {
            db.readUnlock();
        }
    }

    @Override
    public void process(List<AlterClause> alterClauses, String clusterName, Database db, OlapTable olapTable)
            throws DdlException {
        for (AlterClause alterClause : alterClauses) {
            if (alterClause instanceof AddRollupClause) {
                processAddRollup((AddRollupClause) alterClause, db, olapTable);
            } else if (alterClause instanceof DropRollupClause) {
                processDropRollup((DropRollupClause) alterClause, db, olapTable);
            } else {
                Preconditions.checkState(false);
            }
        }
    }

    @Override
    public void cancel(CancelStmt stmt) throws DdlException {
        CancelAlterTableStmt cancelAlterTableStmt = (CancelAlterTableStmt) stmt;

        String dbName = cancelAlterTableStmt.getDbName();
        String tableName = cancelAlterTableStmt.getTableName();
        Preconditions.checkState(!Strings.isNullOrEmpty(dbName));
        Preconditions.checkState(!Strings.isNullOrEmpty(tableName));

        Database db = Catalog.getInstance().getDb(dbName);
        if (db == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_BAD_DB_ERROR, dbName);
        }

        AlterJob rollupJob = null;
        AlterJobV2 rollupJobV2 = null;
        db.writeLock();
        try {
            Table table = db.getTable(tableName);
            if (table == null) {
                ErrorReport.reportDdlException(ErrorCode.ERR_BAD_TABLE_ERROR, tableName);
            }
            if (!(table instanceof OlapTable)) {
                ErrorReport.reportDdlException(ErrorCode.ERR_NOT_OLAP_TABLE, tableName);
            }
            OlapTable olapTable = (OlapTable) table;
            if (olapTable.getState() != OlapTableState.ROLLUP) {
                throw new DdlException("Table[" + tableName + "] is not under ROLLUP. "
                        + "Use 'ALTER TABLE DROP ROLLUP' if you want to.");
            }

            // find from new alter jobs first
            rollupJobV2 = getUnfinishedAlterJobV2(olapTable.getId());
            if (rollupJobV2 == null) {
                rollupJob = getAlterJob(olapTable.getId());
                Preconditions.checkNotNull(rollupJob, olapTable.getId());
                if (rollupJob.getState() == JobState.FINISHED
                        || rollupJob.getState() == JobState.FINISHING
                        || rollupJob.getState() == JobState.CANCELLED) {
                    throw new DdlException("job is already " + rollupJob.getState().name() + ", can not cancel it");
                }
                rollupJob.cancel(olapTable, "user cancelled");
            }
        } finally {
            db.writeUnlock();
        }

        // alter job v2's cancel must be called outside the database lock
        if (rollupJobV2 != null) {
            if (!rollupJobV2.cancel("user cancelled")) {
                throw new DdlException("Job can not be cancelled. State: " + rollupJobV2.getJobState());
            }
            return;
        }

        // handle old alter job
        if (rollupJob != null && rollupJob.getState() == JobState.CANCELLED) {
            jobDone(rollupJob);
        }
    }
}
