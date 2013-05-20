/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.akiban.qp.persistitadapter;

import com.akiban.ais.model.TableIndex;
import com.akiban.qp.expression.IndexBound;
import com.akiban.qp.expression.IndexKeyRange;
import com.akiban.qp.rowtype.IndexRowType;
import com.akiban.server.api.dml.ColumnSelector;
import com.akiban.server.api.dml.scan.LegacyRowWrapper;
import com.akiban.server.api.dml.scan.NewRow;
import com.akiban.server.rowdata.RowData;
import com.akiban.server.rowdata.RowDef;
import com.akiban.server.service.session.Session;
import com.akiban.server.store.Store;

public class OneTableRowCollector extends OperatorBasedRowCollector
{
    OneTableRowCollector(Session session,
                         Store store,
                         RowDef rowDef,
                         int indexId,
                         int scanFlags,
                         RowData start,
                         ColumnSelector startColumns,
                         RowData end,
                         ColumnSelector endColumns)
    {
        super(store, session);
        // rootmostQueryTable
        queryRootTable = rowDef.userTable();
        queryRootType = schema.userTableRowType(queryRootTable);
        requiredUserTables.add(queryRootTable);
        // predicateIndex and predicateType
        predicateIndex = null;
        if (indexId > 0) {
            for (TableIndex userTableIndex : queryRootTable.getIndexesIncludingInternal()) {
                if (userTableIndex.getIndexId() == indexId) {
                    predicateIndex = userTableIndex;
                }
            }
            assert predicateIndex != null : String.format("rowDef: %s, indexId: %s", rowDef, indexId);
        }
        predicateType = queryRootType;
        if (predicateIndex != null) {
            // Index bounds
            IndexRowType indexRowType = schema.indexRowType(predicateIndex).physicalRowType();
            if (start == null && end == null) {
                indexKeyRange = IndexKeyRange.unbounded(indexRowType);
            } else {
                ColumnSelector indexStartSelector = indexSelectorFromTableSelector(predicateIndex, startColumns);
                ColumnSelector indexEndSelector = indexSelectorFromTableSelector(predicateIndex, endColumns);
                IndexBound lo = null;
                if (start != null) {
                    assert start.getRowDefId() == queryRootTable.getTableId();
                    NewRow loRow = new LegacyRowWrapper(rowDef, start);
                    lo = new IndexBound(new NewRowBackedIndexRow(queryRootType, loRow, predicateIndex), indexStartSelector);
                }
                IndexBound hi = null;
                if (end != null) {
                    assert end.getRowDefId() == queryRootTable.getTableId();
                    NewRow hiRow = new LegacyRowWrapper(rowDef, end);
                    hi = new IndexBound(new NewRowBackedIndexRow(queryRootType, hiRow, predicateIndex), indexEndSelector);
                }
                boolean loInclusive = start != null && (scanFlags & (SCAN_FLAGS_START_AT_EDGE | SCAN_FLAGS_START_EXCLUSIVE)) == 0;
                boolean hiInclusive = end != null && (scanFlags & (SCAN_FLAGS_END_AT_EDGE | SCAN_FLAGS_END_EXCLUSIVE)) == 0;
                indexKeyRange =
                    lo == null
                    ? IndexKeyRange.endingAt(indexRowType, hi, hiInclusive) :
                    hi == null
                    ? IndexKeyRange.startingAt(indexRowType, lo, loInclusive)
                    : IndexKeyRange.startingAtAndEndingAt(indexRowType,
                                                          lo,
                                                          loInclusive,
                                                          hi,
                                                          hiInclusive);
            }
        }
    }
}
