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
package org.neo4j.kernel.api.impl.index;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.store.Directory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.neo4j.collection.primitive.PrimitiveLongSet;
import org.neo4j.collection.primitive.PrimitiveLongVisitor;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.helpers.CancellationRequest;
import org.neo4j.helpers.TaskControl;
import org.neo4j.helpers.TaskCoordinator;
import org.neo4j.kernel.api.direct.BoundedIterable;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexAccessor;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.NodePropertyUpdate;
import org.neo4j.kernel.impl.api.index.IndexUpdateMode;
import org.neo4j.storageengine.api.schema.IndexReader;

import static org.neo4j.kernel.api.impl.index.DirectorySupport.deleteDirectoryContents;

abstract class LuceneIndexAccessor implements IndexAccessor
{
    protected final LuceneDocumentStructure documentStructure;
    protected final LuceneReferenceManager<IndexSearcher> searcherManager;
    protected final LuceneIndexWriter writer;

    private final Directory dir;
    private final File dirFile;
    private final int bufferSizeLimit;
    private final TaskCoordinator taskCoordinator = new TaskCoordinator( 10, TimeUnit.MILLISECONDS );

    private final PrimitiveLongVisitor<IOException> removeFromLucene = new PrimitiveLongVisitor<IOException>()
    {
        @Override
        public boolean visited( long nodeId ) throws IOException
        {
            LuceneIndexAccessor.this.remove( nodeId );
            return false;
        }
    };

    // we need this wrapping in order to test the index accessor since the ReferenceManager is not mock friendly
    public interface LuceneReferenceManager<G> extends Closeable
    {
        G acquire() throws IOException;

        boolean maybeRefresh() throws IOException;

        void release( G reference ) throws IOException;

        class Wrap<G> implements LuceneReferenceManager<G>
        {
            private final ReferenceManager<G> delegate;

            Wrap( ReferenceManager<G> delegate )
            {

                this.delegate = delegate;
            }

            @Override
            public G acquire() throws IOException
            {
                return delegate.acquire();
            }

            @Override
            public boolean maybeRefresh() throws IOException
            {
                return delegate.maybeRefresh();
            }

            @Override
            public void release( G reference ) throws IOException
            {
                delegate.release( reference );
            }

            @Override
            public void close() throws IOException
            {
                delegate.close();
            }
        }
    }

    LuceneIndexAccessor( LuceneDocumentStructure documentStructure,
            IndexWriterFactory<LuceneIndexWriter> indexWriterFactory,
            DirectoryFactory dirFactory, File dirFile,
            int bufferSizeLimit ) throws IOException
    {
        this.documentStructure = documentStructure;
        this.dirFile = dirFile;
        this.bufferSizeLimit = bufferSizeLimit;
        this.dir = dirFactory.open( dirFile );
        this.writer = indexWriterFactory.create( dir );
        this.searcherManager = new LuceneReferenceManager.Wrap<>( writer.createSearcherManager() );
    }

    // test only
    LuceneIndexAccessor( LuceneDocumentStructure documentStructure, LuceneIndexWriter writer,
            LuceneReferenceManager<IndexSearcher> searcherManager,
            Directory dir, File dirFile, int bufferSizeLimit )
    {
        this.documentStructure = documentStructure;
        this.writer = writer;
        this.searcherManager = searcherManager;
        this.dir = dir;
        this.dirFile = dirFile;
        this.bufferSizeLimit = bufferSizeLimit;
    }

    @Override
    public IndexUpdater newUpdater( IndexUpdateMode mode )
    {
        switch ( mode )
        {
        case ONLINE:
            return new LuceneIndexUpdater( false );

        case RECOVERY:
            return new LuceneIndexUpdater( true );

        default:
            throw new IllegalArgumentException( "Unsupported update mode: " + mode );
        }
    }

    @Override
    public void drop() throws IOException
    {
        taskCoordinator.cancel();
        closeIndexResources();
        try
        {
            taskCoordinator.awaitCompletion();
        }
        catch ( InterruptedException e )
        {
            throw new IOException( "Interrupted while waiting for concurrent tasks to complete.", e );
        }
        deleteDirectoryContents( dir );
    }

    @Override
    public void force() throws IOException
    {
        writer.commitAsOnline();
        refreshSearcherManager();
    }

    @Override
    public void flush() throws IOException
    {
        refreshSearcherManager();
    }

    @Override
    public void close() throws IOException
    {
        closeIndexResources();
        dir.close();
    }

    private void closeIndexResources() throws IOException
    {
        writer.close();
        searcherManager.close();
    }

    @Override
    public IndexReader newReader()
    {
        try
        {
            final IndexSearcher searcher = searcherManager.acquire();
            final TaskControl token = taskCoordinator.newInstance();
            final Closeable closeable = new Closeable()
            {
                @Override
                public void close() throws IOException
                {
                    searcherManager.release( searcher );
                    token.close();
                }
            };
            return makeNewReader( searcher, closeable, token );
        }
        catch ( IOException e )
        {
            throw new LuceneIndexSearcherReleaseException("Can't release index searcher: " + searcherManager, e);
        }
    }

    protected IndexReader makeNewReader( IndexSearcher searcher, Closeable closeable, CancellationRequest cancellation )
    {
        return new LuceneIndexAccessorReader( searcher, documentStructure, closeable, cancellation, bufferSizeLimit );
    }

    @Override
    public BoundedIterable<Long> newAllEntriesReader()
    {
        return new LuceneAllEntriesIndexAccessorReader( new LuceneAllDocumentsReader( searcherManager ),
                documentStructure );
    }

    @Override
    public ResourceIterator<File> snapshotFiles() throws IOException
    {
        return new LuceneSnapshotter().snapshot( this.dirFile, writer );
    }

    private void addRecovered( long nodeId, Object value ) throws IOException
    {
        writer.updateDocument( documentStructure.newTermForChangeOrRemove( nodeId ),
                documentStructure.documentRepresentingProperty( nodeId, value ) );
    }

    protected void add( long nodeId, Object value ) throws IOException
    {
        writer.addDocument( documentStructure.documentRepresentingProperty( nodeId, value ) );
    }

    protected void change( long nodeId, Object value ) throws IOException
    {
        writer.updateDocument( documentStructure.newTermForChangeOrRemove( nodeId ),
                documentStructure.documentRepresentingProperty( nodeId, value ) );
    }

    protected void remove( long nodeId ) throws IOException
    {
        writer.deleteDocuments( documentStructure.newTermForChangeOrRemove( nodeId ) );
    }

    // This method should be synchronized because we need every thread to perform actual refresh
    // and not just skip it because some other refresh is in progress
    private synchronized void refreshSearcherManager() throws IOException
    {
        searcherManager.maybeRefresh();
    }

    private class LuceneIndexUpdater implements IndexUpdater
    {
        private final boolean isRecovery;

        private LuceneIndexUpdater( boolean isRecovery )
        {
            this.isRecovery = isRecovery;
        }

        @Override
        public void process( NodePropertyUpdate update ) throws IOException
        {
            switch ( update.getUpdateMode() )
            {
            case ADDED:
                if ( isRecovery )
                {
                    addRecovered( update.getNodeId(), update.getValueAfter() );
                }
                else
                {
                    add( update.getNodeId(), update.getValueAfter() );
                }
                break;
            case CHANGED:
                change( update.getNodeId(), update.getValueAfter() );
                break;
            case REMOVED:
                LuceneIndexAccessor.this.remove( update.getNodeId() );
                break;
            default:
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public void close() throws IOException, IndexEntryConflictException
        {
            refreshSearcherManager();
        }

        @Override
        public void remove( PrimitiveLongSet nodeIds ) throws IOException
        {
            nodeIds.visitKeys( removeFromLucene );
        }
    }
}

