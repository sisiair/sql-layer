/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
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

package com.foundationdb.ais.model.validation;

import com.foundationdb.ais.model.AkibanInformationSchema;
import com.foundationdb.ais.model.Group;
import com.foundationdb.ais.model.HasStorage;
import com.foundationdb.ais.model.Index;
import com.foundationdb.ais.model.Sequence;
import com.foundationdb.ais.model.Table;
import com.foundationdb.ais.model.TableIndex;
import com.foundationdb.server.error.StorageDescriptionInvalidException;

import java.util.Collection;

/**
 * Check all <code>StorageDescription</code>s are present and valid.
 */
public class StorageDescriptionsValid implements AISValidation {

    @Override
    public void validate(AkibanInformationSchema ais, AISValidationOutput output) {
        for(Group group : ais.getGroups().values()) {
            checkObject(group, output);
            for(Index index : group.getIndexes()) {
                checkObject(index, output);
            }
        }
        for(Table table : ais.getTables().values()) {
            for(Index index : table.getIndexesIncludingInternal()) {
                checkObject(index, output);
            }
        }
        for (Sequence sequence: ais.getSequences().values()) {
            checkObject(sequence, output);
        }
    }

    private static void checkObject(HasStorage object, AISValidationOutput output) {
        if(object.getStorageDescription() == null) {
            output.reportFailure(new AISValidationFailure(new StorageDescriptionInvalidException(object, "has not been set")));
        }
        else {
            object.getStorageDescription().validate(output);
        }
    }
}
