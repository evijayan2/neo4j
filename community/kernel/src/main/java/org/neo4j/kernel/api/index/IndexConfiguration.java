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
package org.neo4j.kernel.api.index;

import org.neo4j.kernel.impl.store.record.IndexRule;

public enum IndexConfiguration
{
    NON_UNIQUE( false ),
    UNIQUE( true );

    private final boolean unique;

    private IndexConfiguration( boolean unique )
    {
        this.unique = unique;
    }

    public boolean isUnique()
    {
        return unique;
    }

    public static IndexConfiguration of( boolean unique )
    {
        return unique ? UNIQUE : NON_UNIQUE;
    }

    public static IndexConfiguration of( IndexRule rule )
    {
        return of( rule.isConstraintIndex() );
    }
}
