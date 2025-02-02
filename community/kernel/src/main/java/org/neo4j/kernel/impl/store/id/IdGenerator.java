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
package org.neo4j.kernel.impl.store.id;

public interface IdGenerator extends IdSequence
{
    IdRange nextIdBatch( int size );

    /**
     * @param id the highest in use + 1
     */
    void setHighId( long id );
    long getHighId();
    long getHighestPossibleIdInUse();
    void freeId( long id );

    /**
     * Closes the id generator, marking it as clean.
     */
    void close();
    long getNumberOfIdsInUse();
    long getDefragCount();

    /**
     * Closes the id generator as dirty and deletes it right after closed. This operation is safe, in the sense
     * that the id generator file is closed but not marked as clean. This has the net result that a crash in the
     * middle will still leave the file marked as dirty so it will be deleted on the next open call.
     */
    void delete();
}
