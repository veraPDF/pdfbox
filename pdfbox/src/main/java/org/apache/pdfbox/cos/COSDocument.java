/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pdfbox.cos;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdfparser.PDFObjectStreamParser;

/**
 * This is the in-memory representation of the PDF document.  You need to call
 * close() on this object when you are done using it!!
 *
 * @author Ben Litchfield
 * 
 */
public class COSDocument extends COSBase implements Closeable
{

    /**
     * Log instance.
     */
    private static final Log LOG = LogFactory.getLog(COSDocument.class);

    private float version = 1.4f;

    /**
     * Maps ObjectKeys to a COSObject. Note that references to these objects
     * are also stored in COSDictionary objects that map a name to a specific object.
     */
    private final Map<COSObjectKey, COSObject> objectPool =
        new HashMap<COSObjectKey, COSObject>();

    /**
     * Maps object and generation id to object byte offsets.
     */
    private final Map<COSObjectKey, Long> xrefTable =
        new HashMap<COSObjectKey, Long>();

    /**
     * Document trailer dictionary.
     */
    private COSDictionary trailer;

    /** Last document trailer. It differs from the simple trailer only if pdf file is linearized
     */
    private COSDictionary lastTrailer;

    //contains clean first trailer of document
    private COSDictionary firstPageTrailer;
    
    private boolean warnMissingClose = true;
    
    /** 
     * Signal that document is already decrypted. 
     */
    private boolean isDecrypted = false;
    
    private long startXref;
    
    private boolean closed = false;

    private boolean isXRefStream;

    private ScratchFile scratchFile;

    private int postEOFDataSize = -1;
    private boolean isLinearized = false;
    private boolean xrefEOLMarkersComplyPDFA = true;
    private boolean subsectionHeaderSpaceSeparated = true;

	private long headerOffset;
	private String header;
	private int headerCommentByte1;
	private int headerCommentByte2;
	private int headerCommentByte3;
	private int headerCommentByte4;

    private final Set<COSDictionary> signaturesWithGoodByteRange =
            new HashSet<COSDictionary>();

	public int getPostEOFDataSize() {
        return this.postEOFDataSize;
    }

    public void setPostEOFDataSize(int postEOFDataSize) {
        this.postEOFDataSize = postEOFDataSize;
    }

    public boolean isLinearized() {
        return this.isLinearized;
    }

    public void setIsLinearized(boolean isLinearized) {
        this.isLinearized = isLinearized;
    }

    public Boolean isXrefEOLMarkersComplyPDFA() {
        return this.xrefEOLMarkersComplyPDFA;
    }

    public void setXrefEOLMarkersComplyPDFA(Boolean xrefEOLMarkersComplyPDFA) {
        this.xrefEOLMarkersComplyPDFA = xrefEOLMarkersComplyPDFA;
    }

    public Boolean subSectionHeaderSpaceSeparated() {
        return this.subsectionHeaderSpaceSeparated;
    }

    public void setSubsectionHeaderSpaceSeparated(Boolean subsectionHeaderSpaceSeparated) {
        this.subsectionHeaderSpaceSeparated = subsectionHeaderSpaceSeparated;
    }

	public void setHeaderOffset(long headerOffset) {
		this.headerOffset = headerOffset;
	}

	public long getHeaderOffset() {
		return this.headerOffset;
	}

	public String getHeader() {
		return this.header;
	}

	public void setHeader(String header) {
		this.header = header;
	}

	public int getHeaderCommentByte1() {
		return this.headerCommentByte1;
	}

	public void setHeaderCommentByte1(int headerCommentByte1) {
		this.headerCommentByte1 = headerCommentByte1;
	}

	public int getHeaderCommentByte2() {
		return this.headerCommentByte2;
	}

	public void setHeaderCommentByte2(int headerCommentByte2) {
		this.headerCommentByte2 = headerCommentByte2;
	}

	public int getHeaderCommentByte3() {
		return this.headerCommentByte3;
	}

	public void setHeaderCommentByte3(int headerCommentByte3) {
		this.headerCommentByte3 = headerCommentByte3;
	}

	public int getHeaderCommentByte4() {
		return this.headerCommentByte4;
	}

	public void setHeaderCommentByte4(int headerCommentByte4) {
		this.headerCommentByte4 = headerCommentByte4;
	}

    public Set<COSDictionary> getSignaturesWithGoodByteRange() {
        return signaturesWithGoodByteRange;
    }

    /**
     * Constructor.
     *
     * @param useScratchFiles enables the usage of a scratch file if set to true
     *                     
     */
    public COSDocument(boolean useScratchFiles)
    {
        this(null, useScratchFiles);
    }

    /**
     * Constructor that will use a temporary file in the given directory
     * for storage of the PDF streams. The temporary file is automatically
     * removed when this document gets closed.
     *
     * @param scratchDir directory for the temporary file,
     *                   or <code>null</code> to use the system default
     * @param useScratchFiles enables the usage of a scratch file if set to true
     * 
     */
    public COSDocument(File scratchDir, boolean useScratchFiles)
    {
        if (useScratchFiles)
        {
            try 
            {
                scratchFile = new ScratchFile(scratchDir);
            }
            catch (IOException e)
            {
                LOG.error("Can't create temp file, using memory buffer instead", e);
            }
        }
    }

    /**
     * Constructor. Uses memory to store stream.
     */
    public COSDocument()
    {
        this(false);
    }

    /**
     * Creates a new COSStream using the current configuration for scratch files.
     * 
     * @return the new COSStream
     */
    public COSStream createCOSStream()
    {
        return new COSStream(scratchFile);
    }

    /**
     * Creates a new COSStream using the current configuration for scratch files.
     *
     * @param dictionary the corresponding dictionary
     * 
     * @return the new COSStream
     */
    public COSStream createCOSStream(COSDictionary dictionary)
    {
        return new COSStream( dictionary, scratchFile );
    }

    /**
     * This will get the first dictionary object by type.
     *
     * @param type The type of the object.
     *
     * @return This will return an object with the specified type.
     * @throws IOException If there is an error getting the object
     */
    public COSObject getObjectByType( COSName type ) throws IOException
    {
        for( COSObject object : objectPool.values() )
        {
            COSBase realObject = object.getObject();
            if( realObject instanceof COSDictionary )
            {
                try
                {
                    COSDictionary dic = (COSDictionary)realObject;
                    COSBase typeItem = dic.getItem(COSName.TYPE);
                    if (typeItem instanceof COSName)
                    {
                        COSName objectType = (COSName) typeItem;
                        if (objectType.equals(type))
                        {
                            return object;
                        }
                    }
                    else if (typeItem != null)
                    {
                        LOG.debug("Expected a /Name object after /Type, got '" + typeItem + "' instead");
                    }
                }
                catch (ClassCastException e)
                {
                    LOG.warn(e, e);
                }
            }
        }
        return null;
    }

    /**
     * This will get all dictionary objects by type.
     *
     * @param type The type of the object.
     *
     * @return This will return an object with the specified type.
     * @throws IOException If there is an error getting the object
     */
    public List<COSObject> getObjectsByType( String type ) throws IOException
    {
        return getObjectsByType( COSName.getPDFName( type ) );
    }

    /**
     * This will get a dictionary object by type.
     *
     * @param type The type of the object.
     *
     * @return This will return an object with the specified type.
     * @throws IOException If there is an error getting the object
     */
    public List<COSObject> getObjectsByType( COSName type ) throws IOException
    {
        List<COSObject> retval = new ArrayList<COSObject>();
        for( COSObject object : objectPool.values() )
        {
            COSBase realObject = object.getObject();
            if( realObject instanceof COSDictionary )
            {
                try
                {
                    COSDictionary dic = (COSDictionary)realObject;
                    COSBase typeItem = dic.getItem(COSName.TYPE);
                    if (typeItem instanceof COSName)
                    {
                        COSName objectType = (COSName) typeItem;
                        if (objectType.equals(type))
                        {
                            retval.add( object );
                        }
                    }
                    else if (typeItem != null)
                    {
                        LOG.debug("Expected a /Name object after /Type, got '" + typeItem + "' instead");
                    }
                }
                catch (ClassCastException e)
                {
                    LOG.warn(e, e);
                }
            }
        }
        return retval;
    }

    /**
     * Returns the COSObjectKey for a given COS object, or null if there is none.
     * This lookup iterates over all objects in a PDF, which may be slow for large files.
     * 
     * @param object COS object
     * @return key
     */
    public COSObjectKey getKey(COSBase object)
    {
        for (Map.Entry<COSObjectKey, COSObject> entry : objectPool.entrySet())
        {
            if (entry.getValue().getObject() == object)
            {
                return entry.getKey();
            }
        }
        return null;
    }
    
    /**
     * This will print contents to stdout.
     */
    public void print()
    {
        for( COSObject object : objectPool.values() )
        {
            System.out.println( object);
        }
    }

    /**
     * This will set the header version of this PDF document.
     *
     * @param versionValue The version of the PDF document.
     */
    public void setVersion( float versionValue )
    {
        version = versionValue;
    }

    /**
     * This will get the version extracted from the header of this PDF document.
     *
     * @return The header version.
     */
    public float getVersion()
    {
        return version;
    }

    /** 
     * Signals that the document is decrypted completely.
     */
    public void setDecrypted()
    {
        isDecrypted = true;
    }

    /** 
     * Indicates if a encrypted pdf is already decrypted after parsing.
     * 
     *  @return true indicates that the pdf is decrypted.
     */
    public boolean isDecrypted()
    {
        return isDecrypted;
    }
    
    /**
     * This will tell if this is an encrypted document.
     *
     * @return true If this document is encrypted.
     */
    public boolean isEncrypted()
    {
        boolean encrypted = false;
        if( trailer != null )
        {
            encrypted = trailer.getDictionaryObject( COSName.ENCRYPT ) != null;
        }
        return encrypted;
    }

    /**
     * This will get the encryption dictionary if the document is encrypted or null
     * if the document is not encrypted.
     *
     * @return The encryption dictionary.
     */
    public COSDictionary getEncryptionDictionary()
    {
        return (COSDictionary)trailer.getDictionaryObject( COSName.ENCRYPT );
    }

    /**
     * This will set the encryption dictionary, this should only be called when
     * encrypting the document.
     *
     * @param encDictionary The encryption dictionary.
     */
    public void setEncryptionDictionary( COSDictionary encDictionary )
    {
        trailer.setItem( COSName.ENCRYPT, encDictionary );
    }
    
    /**
     * This will get the document ID.
     *
     * @return The document id.
     */
    public COSArray getDocumentID()
    {
        return (COSArray) getTrailer().getDictionaryObject(COSName.ID);
    }

    /**
     * This will set the document ID.
     *
     * @param id The document id.
     */
    public void setDocumentID( COSArray id )
    {
        getTrailer().setItem(COSName.ID, id);
    }
    
    /**
     * This will get the document catalog.
     *
     * Maybe this should move to an object at PDFEdit level
     *
     * @return catalog is the root of all document activities
     *
     * @throws IOException If no catalog can be found.
     */
    public COSObject getCatalog() throws IOException
    {
        COSObject catalog = getObjectByType( COSName.CATALOG );
        if( catalog == null )
        {
            throw new IOException( "Catalog cannot be found" );
        }
        return catalog;
    }

    /**
     * This will get a list of all available objects.
     *
     * @return A list of all objects.
     */
    public List<COSObject> getObjects()
    {
        return new ArrayList<COSObject>(objectPool.values());
    }

    /**
     * This will get the document trailer.
     *
     * @return the document trailer dict
     */
    public COSDictionary getTrailer()
    {
        return trailer;
    }

    /**
     * // MIT added, maybe this should not be supported as trailer is a persistence construct.
     * This will set the document trailer.
     *
     * @param newTrailer the document trailer dictionary
     */
    public void setTrailer(COSDictionary newTrailer)
    {
        trailer = newTrailer;
    }

    /**
     * Returns the first page trailer.
     *
     * @return the document first trailer dict
     */
    public COSDictionary getFirstPageTrailer() {
        return firstPageTrailer;
    }

    public void setFirstPageTrailer(COSDictionary firstPageTrailer) {
        this.firstPageTrailer = firstPageTrailer;
    }

    /**
     * This will get the last document trailer.
     *
     * @return the document last trailer dict
     */
    public COSDictionary getLastTrailer() {
        return lastTrailer;
    }

    /** This will set the document trailer.
     *
     * @param lastTrailer the document trailer dictionary
     */
    public void setLastTrailer(COSDictionary lastTrailer) {
        this.lastTrailer = lastTrailer;
    }
    /**
     * visitor pattern double dispatch method.
     *
     * @param visitor The object to notify when visiting this object.
     * @return any object, depending on the visitor implementation, or null
     * @throws IOException If an error occurs while visiting this object.
     */
    @Override
    public Object accept(ICOSVisitor visitor) throws IOException
    {
        return visitor.visitFromDocument( this );
    }

    /**
     * This will close all storage and delete the tmp files.
     *
     *  @throws IOException If there is an error close resources.
     */
    @Override
    public void close() throws IOException
    {
        if (!closed) 
        {
            // close all open I/O streams
            List<COSObject> list = getObjects();
            if (list != null) 
            {
                for (COSObject object : list) 
                {
                    COSBase cosObject = object.getObject();
                    if (cosObject instanceof COSStream)
                    {
                        ((COSStream)cosObject).close();
                    }
                }
            }

            if (scratchFile != null)
            {
                scratchFile.close();
            }
            closed = true;
        }
    }

    /**
     * Returns true if this document has been closed.
     */
    public boolean isClosed()
    {
        return closed;
    }

    /**
     * Warn the user in the finalizer if he didn't close the PDF document. The method also
     * closes the document just in case, to avoid abandoned temporary files. It's still a good
     * idea for the user to close the PDF document at the earliest possible to conserve resources.
     * @throws IOException if an error occurs while closing the temporary files
     */
    @Override
    protected void finalize() throws IOException
    {
        if (!closed) 
        {
            if (warnMissingClose) 
            {
                LOG.warn( "Warning: You did not close a PDF Document" );
            }
            close();
        }
    }

    /**
     * Controls whether this instance shall issue a warning if the PDF document wasn't closed
     * properly through a call to the {@link #close()} method. If the PDF document is held in
     * a cache governed by soft references it is impossible to reliably close the document
     * before the warning is raised. By default, the warning is enabled.
     * @param warn true enables the warning, false disables it.
     */
    public void setWarnMissingClose(boolean warn)
    {
        this.warnMissingClose = warn;
    }

    /**
     * This method will search the list of objects for types of ObjStm.  If it finds
     * them then it will parse out all of the objects from the stream that is contains.
     *
     * @throws IOException If there is an error parsing the stream.
     */
    public void dereferenceObjectStreams() throws IOException
    {
        for( COSObject objStream : getObjectsByType( COSName.OBJ_STM ) )
        {
            COSStream stream = (COSStream)objStream.getObject();
            PDFObjectStreamParser parser = new PDFObjectStreamParser(stream, this);
            parser.parse();
            for (COSObject next : parser.getObjects())
            {
                COSObjectKey key = new COSObjectKey(next);
                if (objectPool.get(key) == null || objectPool.get(key).getObject() == null
                        // xrefTable stores negated objNr of objStream for objects in objStreams
                        || (xrefTable.containsKey(key)
                            && xrefTable.get(key) == -objStream.getObjectNumber()))
                {
                    COSObject obj = getObjectFromPool(key);
                    obj.setObject(next.getObject());
                }
            }
        }
    }

    /**
     * This will get an object from the pool.
     *
     * @param key The object key.
     *
     * @return The object in the pool or a new one if it has not been parsed yet.
     *
     * @throws IOException If there is an error getting the proxy object.
     */
    public COSObject getObjectFromPool(COSObjectKey key) throws IOException
    {
        COSObject obj = null;
        if( key != null )
        {
            obj = objectPool.get(key);
        }
        if (obj == null)
        {
            // this was a forward reference, make "proxy" object
            obj = new COSObject(null);
            if( key != null )
            {
                obj.setObjectNumber(key.getNumber());
                obj.setGenerationNumber(key.getGeneration());
                objectPool.put(key, obj);
            }
        }
        return obj;
    }

    /**
     * Removes an object from the object pool.
     * @param key the object key
     * @return the object that was removed or null if the object was not found
     */
    public COSObject removeObject(COSObjectKey key)
    {
        return objectPool.remove(key);
    }

    /**
     * Populate XRef HashMap with given values.
     * Each entry maps ObjectKeys to byte offsets in the file.
     * @param xrefTableValues  xref table entries to be added
     */
    public void addXRefTable( Map<COSObjectKey, Long> xrefTableValues )
    {
        xrefTable.putAll( xrefTableValues );
    }

    /**
     * Returns the xrefTable which is a mapping of ObjectKeys
     * to byte offsets in the file.
     * @return mapping of ObjectsKeys to byte offsets
     */
    public Map<COSObjectKey, Long> getXrefTable()
    {
        return xrefTable;
    }

    /**
     * This method set the startxref value of the document. This will only 
     * be needed for incremental updates.
     * 
     * @param startXrefValue the value for startXref
     */
    public void setStartXref(long startXrefValue)
    {
        startXref = startXrefValue;
    }

    /**
     * Return the startXref Position of the parsed document. This will only be needed for incremental updates.
     * 
     * @return a long with the old position of the startxref
     */
    public long getStartXref()
    {
      return startXref;
    }

    /**
     * Determines if the trailer is a XRef stream or not.
     * 
     * @return true if the trailer is a XRef stream
     */
    public boolean isXRefStream()
    {
        return isXRefStream;
    }
    
    /**
     * Sets isXRefStream to the given value.
     * 
     * @param isXRefStreamValue the new value for isXRefStream
     */
    public void setIsXRefStream(boolean isXRefStreamValue)
    {
        isXRefStream = isXRefStreamValue;
    }

}
