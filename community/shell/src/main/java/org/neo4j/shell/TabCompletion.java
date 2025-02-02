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
package org.neo4j.shell;

import java.io.Serializable;
import java.util.Collection;

public class TabCompletion implements Serializable
{
    private static final long serialVersionUID = -3960128927108659417L;

    private final Collection<String> candidates;
    private final int cursor;

    public TabCompletion( Collection<String> candidates, int cursor )
    {
        this.candidates = candidates;
        this.cursor = cursor;
    }

    public Collection<String> getCandidates()
    {
        return candidates;
    }

    public int getCursor()
    {
        return cursor;
    }
}
