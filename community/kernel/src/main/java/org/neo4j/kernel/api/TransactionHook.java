/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
package org.neo4j.kernel.api;

import org.neo4j.storageengine.api.StoreReadLayer;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;

/**
 * A mechanism to augment and monitor transactions before and after commit/rollback.
 */
public interface TransactionHook<OUTCOME extends TransactionHook.Outcome>
{
    public interface Outcome
    {
        boolean isSuccessful();
        Throwable failure();
    }

    OUTCOME beforeCommit( ReadableTransactionState state, KernelTransaction transaction, StoreReadLayer storeReadLayer );
    void afterCommit( ReadableTransactionState state, KernelTransaction transaction, OUTCOME outcome );
    void afterRollback( ReadableTransactionState state, KernelTransaction transaction, OUTCOME outcome );
}
