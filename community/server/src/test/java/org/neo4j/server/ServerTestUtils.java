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
package org.neo4j.server;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import org.neo4j.graphdb.config.Setting;
import org.neo4j.server.configuration.ServerSettings;

public class ServerTestUtils
{
    public static File createTempDir() throws IOException
    {
        return Files.createTempDirectory( "neo4j-test" ).toFile();
    }

    public static File getSharedTestTemporaryFolder()
    {
        try
        {
            return createTempPropertyFile().getParentFile();
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
    }

    public static File createTempPropertyFile() throws IOException
    {
        File file = File.createTempFile( "neo4j", "properties" );
        file.delete();
        return file;
    }

    public static File getRelativeFile( Setting<File> setting ) throws IOException
    {
        return getSharedTestTemporaryFolder()
                .toPath().resolve( setting.getDefaultValue() )
                .toFile();
    }

    public static String getRelativePath( File folder, Setting<File> setting )
    {
        return folder.toPath().resolve( setting.getDefaultValue() ).toString();
    }

    public static Map<String,String> getDefaultRelativeProperties() throws IOException
    {
        File testFolder = getSharedTestTemporaryFolder();
        Map<String,String> settings = new HashMap<>();
        addDefaultRelativeProperties( settings, testFolder );
        return settings;
    }

    public static void addDefaultRelativeProperties( Map<String,String> properties, File temporaryFolder )
    {
        addRelativeProperty( temporaryFolder, properties, ServerSettings.legacy_db_location );
        addRelativeProperty( temporaryFolder, properties, ServerSettings.auth_store );
        addRelativeProperty( temporaryFolder, properties, ServerSettings.tls_certificate_file );
        addRelativeProperty( temporaryFolder, properties, ServerSettings.tls_key_file );
    }

    private static void addRelativeProperty( File temporaryFolder, Map<String,String> properties,
            Setting<File> setting )
    {
        properties.put( setting.name(), getRelativePath( temporaryFolder, setting ) );
    }

    public static void writePropertiesToFile( String outerPropertyName, Map<String, String> properties,
            File propertyFile )
    {
        writePropertyToFile( outerPropertyName, asOneLine( properties ), propertyFile );
    }

    public static void writePropertiesToFile( Map<String, String> properties, File propertyFile )
    {
        Properties props = loadProperties( propertyFile );
        for ( Map.Entry<String, String> entry : properties.entrySet() )
        {
            props.setProperty( entry.getKey(), entry.getValue() );
        }
        storeProperties( propertyFile, props );
    }

    public static String asOneLine( Map<String, String> properties )
    {
        StringBuilder builder = new StringBuilder();
        for ( Map.Entry<String, String> property : properties.entrySet() )
        {
            builder.append( ( builder.length() > 0 ? "," : "" ) );
            builder.append( property.getKey() + "=" + property.getValue() );
        }
        return builder.toString();
    }

    public static void writePropertyToFile( String name, String value, File propertyFile )
    {
        Properties properties = loadProperties( propertyFile );
        properties.setProperty( name, value );
        storeProperties( propertyFile, properties );
    }

    private static void storeProperties( File propertyFile, Properties properties )
    {
        OutputStream out = null;
        try
        {
            out = new FileOutputStream( propertyFile );
            properties.store( out, "" );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
        finally
        {
            safeClose( out );
        }
    }

    private static Properties loadProperties( File propertyFile )
    {
        Properties properties = new Properties();
        if ( propertyFile.exists() )
        {
            InputStream in = null;
            try
            {
                in = new FileInputStream( propertyFile );
                properties.load( in );
            }
            catch ( IOException e )
            {
                throw new RuntimeException( e );
            }
            finally
            {
                safeClose( in );
            }
        }
        return properties;
    }

    private static void safeClose( Closeable closeable )
    {
        if ( closeable != null )
        {
            try
            {
                closeable.close();
            }
            catch ( IOException e )
            {
                e.printStackTrace();
            }
        }
    }

    public static File createTempPropertyFile( File parentDir ) throws IOException
    {
        File file = new File( parentDir, "test-" + new Random().nextInt() + ".properties" );
        file.deleteOnExit();
        return file;
    }

    public interface BlockWithCSVFileURL {
        void execute(String url) throws Exception;
    }

    public static void withCSVFile( int rowCount, BlockWithCSVFileURL block ) throws Exception
    {
        File file = File.createTempFile( "file", ".csv", null );
        try {
            try ( PrintWriter writer = new PrintWriter( file ) ) {
                for (int i = 0; i < rowCount; ++i) {
                    writer.println("1,2,3");
                }
            }

            String url = file.toURI().toURL().toString().replace( "\\", "\\\\" );
            block.execute( url );
        }
        finally
        {
            file.delete();
        }
    }
}
