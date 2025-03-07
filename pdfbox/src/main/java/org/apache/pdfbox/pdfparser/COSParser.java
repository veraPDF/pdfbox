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
package org.apache.pdfbox.pdfparser;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdfparser.XrefTrailerResolver.XRefType;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.PDEncryption;
import org.apache.pdfbox.pdmodel.encryption.SecurityHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.Map.Entry;

import static org.apache.pdfbox.util.Charsets.ISO_8859_1;

/**
 * PDF-Parser which first reads startxref and xref tables in order to know valid objects and parse only these objects.
 * 
 * First {@link PDFParser#parse()} or  {@link FDFParser#parse()} must be called before page objects
 * can be retrieved, e.g. {@link PDFParser#getPDDocument()}.
 * 
 * This class is a much enhanced version of <code>QuickParser</code> presented in <a
 * href="https://issues.apache.org/jira/browse/PDFBOX-1104">PDFBOX-1104</a> by Jeremy Villalobos.
 */
public class COSParser extends BaseParser
{
    private static final String PDF_HEADER = "%PDF-";
    private static final String FDF_HEADER = "%FDF-";
    
    private static final String PDF_DEFAULT_VERSION = "1.4";
    private static final String FDF_DEFAULT_VERSION = "1.0";

    private static final char[] XREF_TABLE = new char[] { 'x', 'r', 'e', 'f' };
    private static final char[] XREF_STREAM = new char[] { '/', 'X', 'R', 'e', 'f' };
    private static final char[] STARTXREF = new char[] { 's','t','a','r','t','x','r','e','f' };

    private static final byte[] ENDSTREAM = new byte[] { E, N, D, S, T, R, E, A, M };

    private static final byte[] ENDOBJ = new byte[] { E, N, D, O, B, J };

    private static final long MINIMUM_SEARCH_OFFSET = 6;
    
    private static final int X = 'x';

    private AccessPermission accessPermission;

    private static final int STRMBUFLEN = 2048;
    private final byte[] strmBuf    = new byte[ STRMBUFLEN ];

    /**
     * Only parse the PDF file minimally allowing access to basic information.
     */
    public static final String SYSPROP_PARSEMINIMAL =
            "org.apache.pdfbox.pdfparser.nonSequentialPDFParser.parseMinimal";

    /**
     * The range within the %%EOF marker will be searched.
     * Useful if there are additional characters after %%EOF within the PDF.
     */
    public static final String SYSPROP_EOFLOOKUPRANGE =
            "org.apache.pdfbox.pdfparser.nonSequentialPDFParser.eofLookupRange";

    /**
     * How many trailing bytes to read for EOF marker.
     */
    private static final int DEFAULT_TRAIL_BYTECOUNT = 2048;
    /**
     * EOF-marker.
     */
    protected static final char[] EOF_MARKER = new char[] { '%', '%', 'E', 'O', 'F' };
    /**
     * obj-marker.
     */
    protected static final char[] OBJ_MARKER = new char[] { 'o', 'b', 'j' };

	/**
	 * Linearization dictionary must be in first 1024 bytes of document
	 */
	private final int LINEARIZATION_SIZE = 1024;

	private long trailerOffset;
    private PDEncryption encryption = null;

    /**
     * file length.
     */
    protected long fileLen;

    /**
     * is parser using auto healing capacity ?
     */
    private boolean isLenient = true;

    protected boolean initialParseDone = false;
    /**
     * Contains all found objects of a brute force search.
     */
    private Map<COSObjectKey, Long> bfSearchCOSObjectKeyOffsets = null;
    private List<Long> bfSearchXRefTablesOffsets = null;
    private List<Long> bfSearchXRefStreamsOffsets = null;

    /**
     * The security handler.
     */
    protected SecurityHandler securityHandler = null;

    /**
     *  how many trailing bytes to read for EOF marker.
     */
    private int readTrailBytes = DEFAULT_TRAIL_BYTECOUNT;

    private static final Log LOG = LogFactory.getLog(COSParser.class);

    /**
     * Collects all Xref/trailer objects and resolves them into single
     * object using startxref reference.
     */
    protected XrefTrailerResolver xrefTrailerResolver = new XrefTrailerResolver();


    /**
     * The prefix for the temp file being used.
     */
    public static final String TMP_FILE_PREFIX = "tmpPDF";

	/**
     * Default constructor.
     */
    public COSParser()
    {
    }

    /**
     * Sets how many trailing bytes of PDF file are searched for EOF marker and 'startxref' marker. If not set we use
     * default value {@link #DEFAULT_TRAIL_BYTECOUNT}.
     *
     * <p>We check that new value is at least 16. However for practical use cases this value should not be lower than
     * 1000; even 2000 was found to not be enough in some cases where some trailing garbage like HTML snippets followed
     * the EOF marker.</p>
     *
     * <p>
     * In case system property {@link #SYSPROP_EOFLOOKUPRANGE} is defined this value will be set on initialization but
     * can be overwritten later.
     * </p>
     *
     * @param byteCount number of trailing bytes
     */
    public void setEOFLookupRange(int byteCount)
    {
        if (byteCount > 15)
        {
            readTrailBytes = byteCount;
        }
    }

    /**
     * Parses cross reference tables.
     *
     * @param startXRefOffset start offset of the first table
     * @return the trailer dictionary
     * @throws IOException if something went wrong
     */
    protected COSDictionary parseXref(long startXRefOffset) throws IOException
    {
        pdfSource.seek(startXRefOffset);
        long startXrefOffset = Math.max(0, parseStartXref());
        startXrefOffset += document.getHeaderOffset();
        // check the startxref offset
        long fixedOffset = checkXRefOffset(startXrefOffset);
        if (fixedOffset > -1)
        {
            startXrefOffset = fixedOffset;
        }
        document.setStartXref(startXrefOffset);
        long prev = startXrefOffset;
        // ---- parse whole chain of xref tables/object streams using PREV reference
        while (prev > 0)
        {
            // seek to xref table
            pdfSource.seek(prev);

            // skip white spaces
            skipSpaces();
            // -- parse xref
            if (pdfSource.peek() == X)
            {
                // xref table and trailer
                // use existing parser to parse xref table
                parseXrefTable(prev);
                // parse the last trailer.
                trailerOffset = pdfSource.getPosition();
                // PDFBOX-1739 skip extra xref entries in RegisSTAR documents
                while (isLenient && pdfSource.peek() != 't')
                {
                    if (pdfSource.getPosition() == trailerOffset)
                    {
                        // warn only the first time
                        LOG.debug("Expected trailer object at position " + trailerOffset
                                + ", keep trying");
                    }
                    readLine();
                }
                if (!parseTrailer())
                {
                    throw new IOException("Expected trailer object at position: "
                            + pdfSource.getPosition());
                }
                COSDictionary trailer = xrefTrailerResolver.getCurrentTrailer();
                // check for a XRef stream, it may contain some object ids of compressed objects 
                if(trailer.containsKey(COSName.XREF_STM))
                {
                    int streamOffset = trailer.getInt(COSName.XREF_STM);
                    // check the xref stream reference
                    fixedOffset = checkXRefStreamOffset(streamOffset, false);
                    if (fixedOffset > -1 && fixedOffset != streamOffset)
                    {
                        streamOffset = (int)fixedOffset;
                        trailer.setInt(COSName.XREF_STM, streamOffset);
                    }
                    if (streamOffset > 0)
                    {
                        pdfSource.seek(streamOffset);
                        skipSpaces();
                        parseXrefObjStream(prev, false);
                    }
                    else
                    {
                        if(isLenient)
                        {
                            LOG.error("Skipped XRef stream due to a corrupt offset:"+streamOffset);
                        }
                        else
                        {
                            throw new IOException("Skipped XRef stream due to a corrupt offset:"+streamOffset);
                        }
                    }
                }
                prev = trailer.getInt(COSName.PREV);
                if (prev > 0)
                {
                    // check the xref table reference
                    fixedOffset = checkXRefOffset(prev);
                    if (fixedOffset > -1 && fixedOffset != prev)
                    {
                        prev = fixedOffset;
                        trailer.setLong(COSName.PREV, prev);
                    }
                }
            }
            else
            {
                // parse xref stream
                prev = parseXrefObjStream(prev, true);
                if (prev > 0)
                {
                    // check the xref table reference
                    fixedOffset = checkXRefOffset(prev);
                    if (fixedOffset > -1 && fixedOffset != prev)
                    {
                        prev = fixedOffset;
                        COSDictionary trailer = xrefTrailerResolver.getCurrentTrailer();
                        trailer.setLong(COSName.PREV, prev);
                    }
                }
            }
        }
        // ---- build valid xrefs out of the xref chain
        xrefTrailerResolver.setStartxref(startXrefOffset);
        COSDictionary trailer = xrefTrailerResolver.getTrailer();
        document.setTrailer(trailer);
        document.setIsXRefStream(XRefType.STREAM == xrefTrailerResolver.getXrefType());
        // check the offsets of all referenced objects
        if (validationParsing) {
			strictCheckXrefOffsets();
        } else {
			checkXrefOffsets();
        }
        // copy xref table
        document.addXRefTable(xrefTrailerResolver.getXrefTable());
        return trailer;
    }

    /**
     * Parses an xref object stream starting with indirect object id.
     *
     * @return value of PREV item in dictionary or <code>-1</code> if no such item exists
     */
    private long parseXrefObjStream(long objByteOffset, boolean isStandalone) throws IOException
    {
        // ---- parse indirect object head
        readObjectNumber();
        readGenerationNumber();
        readExpectedString(OBJ_MARKER, true);

        COSDictionary dict = parseCOSDictionary();
        COSStream xrefStream = parseCOSStream(dict);
        parseXrefStream(xrefStream, (int) objByteOffset, isStandalone);
        xrefStream.close();

        return dict.getLong(COSName.PREV);
    }

    /**
     * Looks for and parses startxref. We first look for last '%%EOF' marker (within last
     * {@link #DEFAULT_TRAIL_BYTECOUNT} bytes (or range set via {@link #setEOFLookupRange(int)}) and go back to find
     * <code>startxref</code>.
     *
     * @return the offset of StartXref
     * @throws IOException If something went wrong.
     */
    protected final long getStartxrefOffset() throws IOException
    {
        byte[] buf;
        long skipBytes;
        // read trailing bytes into buffer
        try
        {
            final int trailByteCount = (fileLen < readTrailBytes) ? (int) fileLen : readTrailBytes;
            buf = new byte[trailByteCount];
            skipBytes = fileLen - trailByteCount;
            pdfSource.seek(skipBytes);
            int off = 0;
            int readBytes;
            while (off < trailByteCount)
            {
                readBytes = pdfSource.read(buf, off, trailByteCount - off);
                // in order to not get stuck in a loop we check readBytes (this should never happen)
                if (readBytes < 1)
                {
                    throw new IOException(
                            "No more bytes to read for trailing buffer, but expected: "
                                    + (trailByteCount - off));
                }
                off += readBytes;
            }
        }
        finally
        {
            pdfSource.seek(0);
        }
        // find last '%%EOF'
        int bufOff = lastIndexOf(EOF_MARKER, buf, buf.length);
        if (bufOff < 0)
        {
            if (validationParsing) {
                // pdf/a-1b specification, clause 6.1.3
                document.setPostEOFDataSize(-1);
            }
            if (isLenient)
            {
                // in lenient mode the '%%EOF' isn't needed
                bufOff = buf.length;
                LOG.debug("Missing end of file marker '" + new String(EOF_MARKER) + "'");
            }
            else if (!validationParsing)
            {
                throw new IOException("Missing end of file marker '" + new String(EOF_MARKER) + "'");
            }
        } else if (validationParsing){
            // If there's more than six bytes after the start offset of the last eof marker
            // or 5th and 6th bytes are not EOL markers we consider the document as an invalid PDF/A document
            // 0x0A - LF (10), 0x0D - CR (13)
			int endOfEOF = bufOff + 5;
			int postEOFDateSize = buf.length - endOfEOF;
			if (postEOFDateSize > 0) {
				if (buf[endOfEOF] == 0x0D) {
					int nextEOL = endOfEOF + 1;
					if (nextEOL < buf.length && buf[nextEOL] == 0x0A) {
						postEOFDateSize -= 2;
					} else {
						postEOFDateSize -= 1;
					}
				} else if (buf[endOfEOF] == 0x0A) {
					postEOFDateSize -= 1;
				}
			}
			this.document.setPostEOFDataSize(postEOFDateSize);
        }
        // find last startxref preceding EOF marker
        bufOff = lastIndexOf(STARTXREF, buf, bufOff);
        long startXRefOffset = skipBytes + bufOff;

        if (bufOff < 0)
        {
            if (isLenient)
            {
                LOG.debug("Can't find offset for startxref");
                return -1;
            }
            else
            {
                throw new IOException("Missing 'startxref' marker.");
            }
        }
        return startXRefOffset;
    }

    /**
     * Searches last appearance of pattern within buffer. Lookup before _lastOff and goes back until 0.
     *
     * @param pattern pattern to search for
     * @param buf buffer to search pattern in
     * @param endOff offset (exclusive) where lookup starts at
     *
     * @return start offset of pattern within buffer or <code>-1</code> if pattern could not be found
     */
    protected int lastIndexOf(final char[] pattern, final byte[] buf, final int endOff)
    {
        final int lastPatternChOff = pattern.length - 1;

        int bufOff = endOff;
        int patOff = lastPatternChOff;
        char lookupCh = pattern[patOff];

        while (--bufOff >= 0)
        {
            if (buf[bufOff] == lookupCh)
            {
                if (--patOff < 0)
                {
                    // whole pattern matched
                    return bufOff;
                }
                // matched current char, advance to preceding one
                lookupCh = pattern[patOff];
            }
            else if (patOff < lastPatternChOff)
            {
                // no char match but already matched some chars; reset
                patOff = lastPatternChOff;
                lookupCh = pattern[patOff];
            }
        }
        return -1;
    }

    /**
     * Return true if parser is lenient. Meaning auto healing capacity of the parser are used.
     *
     * @return true if parser is lenient
     */
    public boolean isLenient()
    {
        return isLenient;
    }

    /**
     * Change the parser leniency flag.
     *
     * This method can only be called before the parsing of the file.
     *
     * @param lenient try to handle malformed PDFs.
     *
     */
    public void setLenient(boolean lenient)
    {
        if (initialParseDone)
        {
            throw new IllegalArgumentException("Cannot change leniency after parsing");
        }
        this.isLenient = lenient;
    }

    /**
     * Creates a unique object id using object number and object generation
     * number. (requires object number &lt; 2^31))
     */
    private long getObjectId(final COSObject obj)
    {
        return obj.getObjectNumber() << 32 | obj.getGenerationNumber();
    }

    /**
     * Adds all from newObjects to toBeParsedList if it is not an COSObject or
     * we didn't add this COSObject already (checked via addedObjects).
     */
    private void addNewToList(final Queue<COSBase> toBeParsedList,
            final Collection<COSBase> newObjects, final Set<Long> addedObjects)
    {
        for (COSBase newObject : newObjects)
        {
            addNewToList(toBeParsedList, newObject, addedObjects);
        }
    }

    /**
     * Adds newObject to toBeParsedList if it is not an COSObject or we didn't
     * add this COSObject already (checked via addedObjects).
     */
    private void addNewToList(final Queue<COSBase> toBeParsedList, final COSBase newObject,
            final Set<Long> addedObjects)
    {
        if (newObject instanceof COSObject)
        {
            final long objId = getObjectId((COSObject) newObject);
            if (!addedObjects.add(objId))
            {
                return;
            }
        }
        toBeParsedList.add(newObject);
    }

    /**
     * Will parse every object necessary to load a single page from the pdf document. We try our
     * best to order objects according to offset in file before reading to minimize seek operations.
     *
     * @param dict the COSObject from the parent pages.
     * @param excludeObjects dictionary object reference entries with these names will not be parsed
     *
     * @throws IOException if something went wrong
     */
    protected void parseDictObjects(COSDictionary dict, COSName... excludeObjects) throws IOException
    {
        // ---- create queue for objects waiting for further parsing
        final Queue<COSBase> toBeParsedList = new LinkedList<COSBase>();
        // offset ordered object map
        final TreeMap<Long, List<COSObject>> objToBeParsed = new TreeMap<Long, List<COSObject>>();
        // in case of compressed objects offset points to stmObj
        final Set<Long> parsedObjects = new HashSet<Long>();
        final Set<Long> addedObjects = new HashSet<Long>();

        addExcludedToList(excludeObjects, dict, parsedObjects);
        addNewToList(toBeParsedList, dict.getValues(), addedObjects);

        // ---- go through objects to be parsed
        while (!(toBeParsedList.isEmpty() && objToBeParsed.isEmpty()))
        {
            // -- first get all COSObject from other kind of objects and
            // put them in objToBeParsed; afterwards toBeParsedList is empty
            COSBase baseObj;
            while ((baseObj = toBeParsedList.poll()) != null)
            {
                if (baseObj instanceof COSDictionary)
                {
                    addNewToList(toBeParsedList, ((COSDictionary) baseObj).getValues(), addedObjects);
                }
                else if (baseObj instanceof COSArray)
                {
					for (COSBase base : ((COSArray) baseObj)) {
						addNewToList(toBeParsedList, base, addedObjects);
					}
                }
                else if (baseObj instanceof COSObject)
                {
                    COSObject obj = (COSObject) baseObj;
                    long objId = getObjectId(obj);
                    COSObjectKey objKey = new COSObjectKey(obj.getObjectNumber(), obj.getGenerationNumber());

                    if (!parsedObjects.contains(objId))
                    {
                        Long fileOffset = xrefTrailerResolver.getXrefTable().get(objKey);
                        // it is allowed that object references point to null,
                        // thus we have to test
                        if (fileOffset != null && fileOffset != 0)
                        {
                            if (fileOffset > 0)
                            {
                                objToBeParsed.put(fileOffset, Collections.singletonList(obj));
                            }
                            else
                            {
                                // negative offset means we have a compressed
                                // object within object stream;
                                // get offset of object stream
                                fileOffset = xrefTrailerResolver.getXrefTable().get(
                                        new COSObjectKey((int)-fileOffset, 0));
                                if ((fileOffset == null) || (fileOffset <= 0))
                                {
                                    throw new IOException(
                                            "Invalid object stream xref object reference for key '" + objKey + "': "
                                                    + fileOffset);
                                }

                                List<COSObject> stmObjects = objToBeParsed.get(fileOffset);
                                if (stmObjects == null)
                                {
                                    stmObjects = new ArrayList<COSObject>();
                                    objToBeParsed.put(fileOffset, stmObjects);
                                }
                                stmObjects.add(obj);
                            }
                        }
                        else
                        {
                            // NULL object
                            COSObject pdfObject = document.getObjectFromPool(objKey);
                            pdfObject.setObject(COSNull.NULL);
                        }
                    }
                }
            }

            // ---- read first COSObject with smallest offset
            // resulting object will be added to toBeParsedList
            if (objToBeParsed.isEmpty())
            {
                break;
            }

            for (COSObject obj : objToBeParsed.remove(objToBeParsed.firstKey()))
            {
                COSBase parsedObj = parseObjectDynamically(obj, false);

                obj.setObject(parsedObj);
                addNewToList(toBeParsedList, parsedObj, addedObjects);

                parsedObjects.add(getObjectId(obj));
            }
        }
    }

    // add objects not to be parsed to list of already parsed objects
    private void addExcludedToList(COSName[] excludeObjects, COSDictionary dict, final Set<Long> parsedObjects)
    {
        if (excludeObjects != null)
        {
            for (COSName objName : excludeObjects)
            {
                COSBase baseObj = dict.getItem(objName);
                if (baseObj instanceof COSObject)
                {
                    parsedObjects.add(getObjectId((COSObject) baseObj));
                }
            }
        }
    }

	/**
	 * Parse all objects of document according to xref table
	 */
	protected void parseSuspensionObjects()
	{
		for (COSObjectKey key : document.getXrefTable().keySet())
		{
			try
			{
				long position = document.getXrefTable().get(key);
				if (position < 0)
				{
					position = xrefTrailerResolver.getXrefTable().get(
							new COSObjectKey(-position, key.getGeneration()));
				}
                //this is required to support pdf files with junk before header
				pdfSource.seek(position + this.document.getHeaderOffset());
				COSObject suspensionObject = document.getObjectFromPool(key);
				parseObjectDynamically(suspensionObject, false);
			} catch (IOException e)
			{
				LOG.error(e);
			}
		}
	}

    /**
     * This will parse the next object from the stream and add it to the local state.
     *
     * @param obj object to be parsed (we only take object number and generation number for lookup start offset)
     * @param requireExistingNotCompressedObj if <code>true</code> object to be parsed must not be contained within
     * compressed stream
     * @return the parsed object (which is also added to document object)
     *
     * @throws IOException If an IO error occurs.
     */
    protected final COSBase parseObjectDynamically(COSObject obj,
            boolean requireExistingNotCompressedObj) throws IOException
    {
        return parseObjectDynamically(obj.getObjectNumber(),
				obj.getGenerationNumber(), requireExistingNotCompressedObj);
    }

    /**
     * This will parse the next object from the stream and add it to the local state.
     * It's reduced to parsing an indirect object.
     *
     * @param objNr object number of object to be parsed
     * @param objGenNr object generation number of object to be parsed
     * @param requireExistingNotCompressedObj if <code>true</code> the object to be parsed must be defined in xref
     * (comment: null objects may be missing from xref) and it must not be a compressed object within object stream
     * (this is used to circumvent being stuck in a loop in a malicious PDF)
     *
     * @return the parsed object (which is also added to document object)
     *
     * @throws IOException If an IO error occurs.
     */
    protected COSBase parseObjectDynamically(long objNr, int objGenNr,
            boolean requireExistingNotCompressedObj) throws IOException
    {
        // ---- create object key and get object (container) from pool
        final COSObjectKey objKey = new COSObjectKey(objNr, objGenNr);
        final COSObject pdfObject = document.getObjectFromPool(objKey);

        if (pdfObject.getObject() == null)
        {
            // not previously parsed
            // ---- read offset or object stream object number from xref table
            Long offsetOrObjstmObNr = xrefTrailerResolver.getXrefTable().get(objKey);

            // sanity test to circumvent loops with broken documents
            if (requireExistingNotCompressedObj
                    && ((offsetOrObjstmObNr == null) || (offsetOrObjstmObNr <= 0)))
            {
                throw new IOException("Object must be defined and must not be compressed object: "
                        + objKey.getNumber() + ":" + objKey.getGeneration());
            }

            if (offsetOrObjstmObNr == null)
            {
                // not defined object -> NULL object (Spec. 1.7, chap. 3.2.9)
                pdfObject.setObject(COSNull.NULL);
            }
            else if (offsetOrObjstmObNr > 0)
            {
                //this is required to support pdf files with junk before header
                offsetOrObjstmObNr += this.document.getHeaderOffset();
                // offset of indirect object in file
                parseFileObject(offsetOrObjstmObNr, objKey, objNr, objGenNr, pdfObject);
            }
            else
            {
                // xref value is object nr of object stream containing object to be parsed
                // since our object was not found it means object stream was not parsed so far
                parseObjectStream((int) -offsetOrObjstmObNr);
            }
        }
        COSBase object = pdfObject.getObject();
        object.setKey(objKey);
        return object;
    }

    private void parseFileObject(Long offsetOrObjstmObNr, final COSObjectKey objKey, long objNr, int objGenNr, final COSObject pdfObject) throws IOException
    {
        // ---- go to object start
        pdfSource.seek(offsetOrObjstmObNr);
        if (validationParsing) {
            //Check that if offset doesn't point to obj key there is eol character before obj key
            //pdf/a-1b spec, clause 6.1.8
            skipSpaces();
            pdfSource.seek(pdfSource.getPosition() - 1);
            if (!isEOL(pdfSource.read())) {
                pdfObject.setHeaderOfObjectComplyPDFA(Boolean.FALSE);
            }
        }

        // ---- we must have an indirect object
        final long readObjNr = readObjectNumber();
        if (validationParsing && ((pdfSource.read() != 32) || skipSpaces() > 0)) {
            //check correct spacing (6.1.8 clause)
            pdfObject.setHeaderFormatComplyPDFA(Boolean.FALSE);
        }
        final int readObjGen = readGenerationNumber();
        if (validationParsing && ((pdfSource.read() != 32) || skipSpaces() > 0)) {
            //check correct spacing (6.1.8 clause)
            pdfObject.setHeaderFormatComplyPDFA(Boolean.FALSE);
        }
        //while parsing pdf document for further validation we don't want to skip any spaces
        readExpectedString(OBJ_MARKER, !validationParsing);

        // ---- consistency check
        if ((readObjNr != objKey.getNumber()) || (readObjGen != objKey.getGeneration()))
        {
			String message = "XREF for " + objKey.getNumber() + ":"
					+ objKey.getGeneration() + " points to wrong object: " + readObjNr
					+ ":" + readObjGen;
			if (validationParsing) {
				LOG.error(message);
				pdfObject.setObject(COSNull.NULL);
				return;
			} else {
				throw new IOException(message);
			}
        }

        if (validationParsing && !isEOL()) {
            // eol marker shall follow the "obj" keyword
            pdfObject.setHeaderOfObjectComplyPDFA(Boolean.FALSE);
        }
        COSBase pb = parseDirObject();
        int eolMarker = 0;
        if (validationParsing) {
            // eolMarker stores symbol before endobj or stream keyword for pdf/a validation
            skipSpaces();
            pdfSource.seek(pdfSource.getPosition() - 1);
            eolMarker = pdfSource.read();
        }
        String endObjectKey = readString();

        if (endObjectKey.equals(STREAM_STRING))
        {
            pdfSource.rewind(endObjectKey.getBytes(ISO_8859_1).length);
            if (pb instanceof COSDictionary)
            {
                COSStream stream = parseCOSStream((COSDictionary) pb);

                if (securityHandler != null)
                {
                    securityHandler.decryptStream(stream, objNr, objGenNr);
                }
                pb = stream;
            }
            else
            {
                // this is not legal
                // the combination of a dict and the stream/endstream
                // forms a complete stream object
                throw new IOException("Stream not preceded by dictionary (offset: "
                        + offsetOrObjstmObNr + ").");
            }

            skipSpaces();
            if (validationParsing) {
                pdfSource.rewind(1);
                eolMarker = pdfSource.read();
                endObjectKey = readLineWithoutWhitespacesSkip();
            } else {
                endObjectKey = readLine();
            }
            // we have case with a second 'endstream' before endobj
            if (!endObjectKey.startsWith(ENDOBJ_STRING) && endObjectKey.startsWith(ENDSTREAM_STRING))
            {
                endObjectKey = endObjectKey.substring(9).trim();
                if (endObjectKey.length() == 0)
                {
                    if (validationParsing) {
                        skipSpaces();
                        endObjectKey = readLineWithoutWhitespacesSkip();
                        eolMarker = pdfSource.read();
                    } else {
                        // no other characters in extra endstream line
                        // read next line
                        endObjectKey = readLine();
                    }
                }
            }
        }
        else if (securityHandler != null)
        {
            securityHandler.decrypt(pb, objNr, objGenNr);
        }

        //pdf/a-1b clause 6.1.8
        if (validationParsing && !isEOL(eolMarker)) {
            pdfObject.setEndOfObjectComplyPDFA(Boolean.FALSE);
        }
        pdfObject.setObject(pb);

        if (!endObjectKey.startsWith(ENDOBJ_STRING))
        {
            if (isLenient)
            {
                LOG.debug("Object (" + readObjNr + ":" + readObjGen + ") at offset "
                        + offsetOrObjstmObNr + " does not end with 'endobj' but with '"
                        + endObjectKey + "'");
            }
            else
            {
                throw new IOException("Object (" + readObjNr + ":" + readObjGen
                        + ") at offset " + offsetOrObjstmObNr
                        + " does not end with 'endobj' but with '" + endObjectKey + "'");
            }
        }

        eolMarker = pdfSource.read();
        if (!isEOL(eolMarker)) {
            pdfObject.setEndOfObjectComplyPDFA(Boolean.FALSE);
            pdfSource.rewind(1);
        }
    }

    private void parseObjectStream(int objstmObjNr) throws IOException
    {
        final COSBase objstmBaseObj = parseObjectDynamically(objstmObjNr, 0, true);
        if (objstmBaseObj instanceof COSStream)
        {
            // parse object stream
            PDFObjectStreamParser parser = new PDFObjectStreamParser((COSStream) objstmBaseObj, document);
            parser.parse();

            // get set of object numbers referenced for this object stream
            final Set<Long> refObjNrs = xrefTrailerResolver.getContainedObjectNumbers(objstmObjNr);

            // register all objects which are referenced to be contained in object stream
            for (COSObject next : parser.getObjects())
            {
                COSObjectKey stmObjKey = new COSObjectKey(next);
                if (refObjNrs.contains(stmObjKey.getNumber()))
                {
                    COSObject stmObj = document.getObjectFromPool(stmObjKey);
                    stmObj.setObject(next.getObject());
                }
            }
        }
    }

    private boolean inGetLength = false;

    /**
     * Returns length value referred to or defined in given object.
     */
    private COSNumber getLength(final COSBase lengthBaseObj) throws IOException
    {
        if (lengthBaseObj == null)
        {
            return null;
        }

        if (inGetLength)
        {
            throw new IOException("Loop while reading length from " + lengthBaseObj);
        }

        COSNumber retVal = null;

        try
        {
            inGetLength = true;
            // maybe length was given directly
            if (lengthBaseObj instanceof COSNumber)
            {
                retVal = (COSNumber) lengthBaseObj;
            }
            // length in referenced object
            else if (lengthBaseObj instanceof COSObject)
            {
                COSObject lengthObj = (COSObject) lengthBaseObj;
                if (lengthObj.getObject() == null)
                {
                    // not read so far, keep current stream position
                    final long curFileOffset = pdfSource.getPosition();
                    parseObjectDynamically(lengthObj, true);
                    // reset current stream position
                    pdfSource.seek(curFileOffset);
                    if (lengthObj.getObject() == null)
                    {
                        throw new IOException("Length object content was not read.");
                    }
                }
                if (!(lengthObj.getObject() instanceof COSNumber))
                {
                    throw new IOException("Wrong type of referenced length object " + lengthObj
                            + ": " + lengthObj.getObject().getClass().getSimpleName());
                }
                retVal = (COSNumber) lengthObj.getObject();
            }
            else
            {
                throw new IOException("Wrong type of length object: "
                        + lengthBaseObj.getClass().getSimpleName());
            }
        }
        finally
        {
            inGetLength = false;
        }
        return retVal;
    }

    private static final int STREAMCOPYBUFLEN = 8192;
    private final byte[] streamCopyBuf = new byte[STREAMCOPYBUFLEN];

    /**
     * This will read a COSStream from the input stream using length attribute within dictionary. If
     * length attribute is a indirect reference it is first resolved to get the stream length. This
     * means we copy stream data without testing for 'endstream' or 'endobj' and thus it is no
     * problem if these keywords occur within stream. We require 'endstream' to be found after
     * stream data is read.
     *
     * @param dic dictionary that goes with this stream.
     *
     * @return parsed pdf stream.
     *
     * @throws IOException if an error occurred reading the stream, like problems with reading
     * length attribute, stream does not end with 'endstream' after data read, stream too short etc.
     */
    protected COSStream parseCOSStream(COSDictionary dic) throws IOException
    {
        final COSStream stream = document.createCOSStream(dic);
        OutputStream out = null;
        try
        {
            // read 'stream'; this was already tested in parseObjectsDynamically()
            readString();

            // pdf/a-1b specification, clause 6.1.7
            if (validationParsing) {
                checkStreamSpacings(stream);
                stream.setOriginLength(pdfSource.getPosition());
            }

            skipWhiteSpaces();

            /*
             * This needs to be dic.getItem because when we are parsing, the underlying object might still be null.
             */
            COSNumber streamLengthObj = getLength(dic.getItem(COSName.LENGTH));
            if (streamLengthObj == null)
            {
                if (isLenient)
                {
                   LOG.debug("The stream doesn't provide any stream length, using fallback readUntilEnd, at offset "
                        + pdfSource.getPosition());
                }
                else
                {
                    throw new IOException("Missing length for stream.");
                }
            }

            // get output stream to copy data to
            if (streamLengthObj != null && validateStreamLength(streamLengthObj.longValue()))
            {
                out = stream.createFilteredStream(streamLengthObj);
                readValidStream(out, streamLengthObj);
            }
            else
            {
                out = stream.createFilteredStream();
                readUntilEndStream(new EndstreamOutputStream(out));
            }

            // pdf/a-1b specification, clause 6.1.7
            if (validationParsing) {
                checkEndStreamSpacings(stream, streamLengthObj.longValue());
            }

            String endStream = readString();
            if (endStream.equals("endobj") && isLenient)
            {
                LOG.debug("stream ends with 'endobj' instead of 'endstream' at offset "
                        + pdfSource.getPosition());
                if (validationParsing) {
                    stream.setEndstreamKeywordEOLCompliant(Boolean.FALSE);
                }
                // avoid follow-up warning about missing endobj
                pdfSource.rewind(ENDOBJ.length);
            }
            else if (endStream.length() > 9 && isLenient && endStream.substring(0,9).equals(ENDSTREAM_STRING))
            {
                LOG.debug("stream ends with '" + endStream + "' instead of 'endstream' at offset "
                        + pdfSource.getPosition());
                if (validationParsing) {
                    stream.setEndstreamKeywordEOLCompliant(Boolean.FALSE);
                }
                // unread the "extra" bytes
                pdfSource.rewind(endStream.substring(9).getBytes(ISO_8859_1).length);
            }
            else if (!endStream.equals(ENDSTREAM_STRING))
            {
                throw new IOException(
                        "Error reading stream, expected='endstream' actual='"
                        + endStream + "' at offset " + pdfSource.getPosition());
            }
        }
        finally
        {
            if (out != null)
            {
                out.close();
            }
        }
        return stream;
    }

    private void checkStreamSpacings(COSStream stream) throws IOException {
        int whiteSpace = pdfSource.read();
        if (whiteSpace == 13) {
            whiteSpace = pdfSource.read();
            if (whiteSpace != 10) {
                stream.setStreamKeywordCRLFCompliant(Boolean.FALSE);
                pdfSource.rewind(1);
            }
        } else if (whiteSpace != 10) {
            LOG.debug("Stream at " + pdfSource.getPosition() + " offset has no EOL marker.");
            stream.setStreamKeywordCRLFCompliant(Boolean.FALSE);
            pdfSource.rewind(1);
        }
    }

    private void checkEndStreamSpacings(COSStream stream, long expectedLength) throws IOException {
		skipSpaces();

		byte eolCount = 0;
		long approximateLength = pdfSource.getPosition() - stream.getOriginLength();
		long diff = approximateLength - expectedLength;

		pdfSource.rewind(2);
		int firstSymbol = pdfSource.read();
		int secondSymbol = pdfSource.read();
		if (secondSymbol == 10) {
			if (firstSymbol == 13) {
				eolCount = (byte) (diff == 1 ? 1 : 2);
			} else {
				eolCount = 1;
			}
		} else if (secondSymbol == 13) {
			eolCount = 1;
		} else {
			LOG.debug("End of stream at " + pdfSource.getPosition() + " offset has no contain EOL marker.");
			stream.setEndstreamKeywordEOLCompliant(Boolean.FALSE);
		}

		stream.setOriginLength(approximateLength - eolCount);
    }

    /**
     * This method will read through the current stream object until
     * we find the keyword "endstream" meaning we're at the end of this
     * object. Some pdf files, however, forget to write some endstream tags
     * and just close off objects with an "endobj" tag so we have to handle
     * this case as well.
     * 
     * This method is optimized using buffered IO and reduced number of
     * byte compare operations.
     * 
     * @param out  stream we write out to.
     * 
     * @throws IOException if something went wrong
     */
    private void readUntilEndStream( final OutputStream out ) throws IOException
    {
        int bufSize;
        int charMatchCount = 0;
        byte[] keyw = ENDSTREAM;
        
        // last character position of shortest keyword ('endobj')
        final int quickTestOffset = 5;
        
        // read next chunk into buffer; already matched chars are added to beginning of buffer
        while ( ( bufSize = pdfSource.read( strmBuf, charMatchCount, STRMBUFLEN - charMatchCount ) ) > 0 ) 
        {
            bufSize += charMatchCount;
            
            int bIdx = charMatchCount;
            int quickTestIdx;
        
            // iterate over buffer, trying to find keyword match
            for ( int maxQuicktestIdx = bufSize - quickTestOffset; bIdx < bufSize; bIdx++ ) 
            {
                // reduce compare operations by first test last character we would have to
                // match if current one matches; if it is not a character from keywords
                // we can move behind the test character; this shortcut is inspired by the 
                // Boyer-Moore string search algorithm and can reduce parsing time by approx. 20%
                quickTestIdx = bIdx + quickTestOffset;
                if (charMatchCount == 0 && quickTestIdx < maxQuicktestIdx)
                {                    
                    final byte ch = strmBuf[quickTestIdx];
                    if ( ( ch > 't' ) || ( ch < 'a' ) ) 
                    {
                        // last character we would have to match if current character would match
                        // is not a character from keywords -> jump behind and start over
                        bIdx = quickTestIdx;
                        continue;
                    }
                }
                
                // could be negative - but we only compare to ASCII
                final byte ch = strmBuf[bIdx];
            
                if ( ch == keyw[ charMatchCount ] ) 
                {
                    if ( ++charMatchCount == keyw.length ) 
                    {
                        // match found
                        bIdx++;
                        break;
                    }
                } 
                else 
                {
                    if ( ( charMatchCount == 3 ) && ( ch == ENDOBJ[ charMatchCount ] ) ) 
                    {
                        // maybe ENDSTREAM is missing but we could have ENDOBJ
                        keyw = ENDOBJ;
                        charMatchCount++;
                    } 
                    else 
                    {
                        // no match; incrementing match start by 1 would be dumb since we already know 
                        // matched chars depending on current char read we may already have beginning 
                        // of a new match: 'e': first char matched; 'n': if we are at match position 
                        // idx 7 we already read 'e' thus 2 chars matched for each other char we have 
                        // to start matching first keyword char beginning with next read position
                        charMatchCount = ( ch == E ) ? 1 : ( ( ch == N ) && ( charMatchCount == 7 ) ) ? 2 : 0;
                        // search again for 'endstream'
                        keyw = ENDSTREAM;
                    }
                } 
            }
            
            int contentBytes = Math.max( 0, bIdx - charMatchCount );
            
            // write buffer content until first matched char to output stream
            if ( contentBytes > 0 )
            {
                out.write( strmBuf, 0, contentBytes );
            }
            if ( charMatchCount == keyw.length ) 
            {
                // keyword matched; unread matched keyword (endstream/endobj) and following buffered content
                pdfSource.rewind( bufSize - contentBytes );
                break;
            } 
            else 
            {
                // copy matched chars at start of buffer
                System.arraycopy( keyw, 0, strmBuf, 0, charMatchCount );
            }            
        }
        // this writes a lonely CR or drops trailing CR LF and LF
        out.flush();
    }

    private void readValidStream(OutputStream out, COSNumber streamLengthObj) throws IOException
    {
        long remainBytes = streamLengthObj.longValue();
        while (remainBytes > 0)
        {
            final int chunk = (remainBytes > STREAMCOPYBUFLEN) ? STREAMCOPYBUFLEN : (int) remainBytes;
            final int readBytes = pdfSource.read(streamCopyBuf, 0, chunk);
            if (readBytes <= 0)
            {
                // shouldn't happen, the stream length has already been validated
                throw new IOException("read error at offset " + pdfSource.getPosition()
                        + ": expected " + chunk + " bytes, but read() returns " + readBytes);
            }
            out.write(streamCopyBuf, 0, readBytes);
            remainBytes -= readBytes;
        }
    }

    private boolean validateStreamLength(long streamLength) throws IOException
    {
        boolean streamLengthIsValid = true;
        long originOffset = pdfSource.getPosition();
        long expectedEndOfStream = originOffset + streamLength;
        if (expectedEndOfStream > fileLen)
        {
            streamLengthIsValid = false;
            LOG.debug("The end of the stream is out of range, using workaround to read the stream, "
                    + "stream start position: " + originOffset + ", length: " + streamLength
                    + ", expected end position: " + expectedEndOfStream);
        }
        else
        {
            pdfSource.seek(expectedEndOfStream);
            skipSpaces();
            if (!isString(ENDSTREAM))
            {
                streamLengthIsValid = false;
                LOG.debug("The end of the stream doesn't point to the correct offset, using workaround to read the stream, "
                        + "stream start position: " + originOffset + ", length: " + streamLength
                        + ", expected end position: " + expectedEndOfStream);
            }
            pdfSource.seek(originOffset);
        }
        return streamLengthIsValid;
    }

    /**
     * Check if the cross reference table/stream can be found at the current offset.
     *
     * @param startXRefOffset
     * @return the revised offset
     * @throws IOException
     */
    private long checkXRefOffset(long startXRefOffset) throws IOException
    {
        // repair mode isn't available in non-lenient mode
        if (!isLenient)
        {
            return startXRefOffset;
        }
        pdfSource.seek(startXRefOffset);
        if (pdfSource.peek() == X && isString(XREF_TABLE))
        {
            return startXRefOffset;
        }
        if (startXRefOffset > 0)
        {
            long fixedOffset = checkXRefStreamOffset(startXRefOffset, true);
            if (fixedOffset > -1)
            {
                return fixedOffset;
            }
        }
        // try to find a fixed offset
        return calculateXRefFixedOffset(startXRefOffset, false);
    }

    /**
     * Check if the cross reference stream can be found at the current offset.
     *
     * @param startXRefOffset the expected start offset of the XRef stream
     * @param checkOnly check only but don't repair the offset if set to true
     * @return the revised offset
     * @throws IOException if something went wrong
     */
    private long checkXRefStreamOffset(long startXRefOffset, boolean checkOnly) throws IOException
    {
        // repair mode isn't available in non-lenient mode
        if (!isLenient || startXRefOffset == 0)
        {
            return startXRefOffset;
        }
        // seek to offset-1 
        pdfSource.seek(startXRefOffset-1);
        int nextValue = pdfSource.read();
        // the first character has to be a whitespace, and then a digit
        if (isWhitespace(nextValue) && isDigit())
        {
            try
            {
                // it's a XRef stream
                readObjectNumber();
                readGenerationNumber();
                readExpectedString(OBJ_MARKER, true);
                pdfSource.seek(startXRefOffset);
                return startXRefOffset;
            }
            catch (IOException exception)
            {
                // there wasn't an object of a xref stream
                // try to repair the offset
                pdfSource.seek(startXRefOffset);
            }
        }
        // try to find a fixed offset
        return checkOnly ? -1 : calculateXRefFixedOffset(startXRefOffset, true);
    }

    /**
     * Try to find a fixed offset for the given xref table/stream.
     *
     * @param objectOffset the given offset where to look at
     * @param streamsOnly search for xref streams only
     * @return the fixed offset
     *
     * @throws IOException if something went wrong
     */
    private long calculateXRefFixedOffset(long objectOffset, boolean streamsOnly) throws IOException
    {
        if (objectOffset < 0)
        {
            LOG.error("Invalid object offset " + objectOffset + " when searching for a xref table/stream");
            return 0;
        }
        // start a brute force search for all xref tables and try to find the offset we are looking for
        long newOffset = bfSearchForXRef(objectOffset, streamsOnly);
        if (newOffset > -1)
        {
            LOG.debug("Fixed reference for xref table/stream " + objectOffset + " -> " + newOffset);
            return newOffset;
        }
        LOG.error("Can't find the object axref table/stream at offset " + objectOffset);
        return 0;
    }

    /**
     * Check the XRef table by dereferencing all objects and fixing the offset if necessary.
     *
     * @throws IOException if something went wrong.
     */
    private void checkXrefOffsets() throws IOException
    {
        // repair mode isn't available in non-lenient mode
        if (!isLenient)
        {
            return;
        }
        Map<COSObjectKey, Long> xrefOffset = xrefTrailerResolver.getXrefTable();
        if (xrefOffset != null)
        {
            boolean bruteForceSearch = false;
            for (Entry<COSObjectKey, Long> objectEntry : xrefOffset.entrySet())
            {
                COSObjectKey objectKey = objectEntry.getKey();
                Long objectOffset = objectEntry.getValue();
                // a negative offset number represents a object number itself
                // see type 2 entry in xref stream
                if (objectOffset != null && objectOffset >= 0
                        && !checkObjectKeys(objectKey, objectOffset))
                {
                    LOG.debug("Stop checking xref offsets as at least one couldn't be dereferenced");
                    bruteForceSearch = true;
                    break;
                }
            }
            if (bruteForceSearch)
            {
                bfSearchForObjects();
                if (bfSearchCOSObjectKeyOffsets != null && !bfSearchCOSObjectKeyOffsets.isEmpty())
                {
                    LOG.debug("Replaced read xref table with the results of a brute force search");
                    xrefOffset.putAll(bfSearchCOSObjectKeyOffsets);
                }
            }
        }
    }

    /**
     * Check the XRef table by dereferencing all objects and fixing the offset if necessary.
     * Doesn't store objects with corrupted offset
     *
     * @throws IOException if something went wrong.
     */
    private void strictCheckXrefOffsets() throws IOException {
        Map<COSObjectKey, Long> xrefOffset = xrefTrailerResolver.getXrefTable();
        if (xrefOffset != null) {
            List<COSObjectKey> objectsToRemove = new ArrayList<COSObjectKey>();
            for (Entry<COSObjectKey, Long> objectEntry : xrefOffset.entrySet()) {
                COSObjectKey objectKey = objectEntry.getKey();
                Long objectOffset = objectEntry.getValue();
                //this is required to support pdf files with junk before header
                objectOffset += this.document.getHeaderOffset();
                // a negative offset number represents a object number itself
                // see type 2 entry in xref stream
                if (objectOffset != null && objectOffset >= 0
                        && !checkObjectKeys(objectKey, objectOffset)) {
                    objectsToRemove.add(objectKey);
                    LOG.debug("Object " + objectKey + " has invalid offset");
                }
            }
            for (COSObjectKey key : objectsToRemove) {
                xrefOffset.remove(key);
            }
        }
    }

    /**
     * Check if the given object can be found at the given offset.
     *
     * @param objectKey the object we are looking for
     * @param offset the offset where to look
     * @return returns true if the given object can be dereferenced at the given offset
     * @throws IOException if something went wrong
     */
    private boolean checkObjectKeys(COSObjectKey objectKey, long offset) throws IOException
    {
        // there can't be any object at the very beginning of a pdf
        if (offset < MINIMUM_SEARCH_OFFSET)
        {
            return false;
        }
        long objectNr = objectKey.getNumber();
        int objectGen = objectKey.getGeneration();
        long originOffset = pdfSource.getPosition();
        pdfSource.seek(offset);
        String objectString = createObjectString(objectNr, objectGen);
        try
        {
            //isObjHeader deals with objects with extra whitespaces between header elements
            if (validationParsing ? isObjHeader(objectString) : isString(objectString.getBytes(ISO_8859_1)))
            {
                // everything is ok, return origin object key
                pdfSource.seek(originOffset);
                return true;
            }
        }
        catch (IOException exception)
        {
            // Swallow the exception, obviously there isn't any valid object number
        }
        finally
        {
            pdfSource.seek(originOffset);
        }
        // no valid object number found
        return false;
    }
    /**
     * Create a string for the given object id.
     *
     * @param objectID the object id
     * @param genID the generation id
     * @return the generated string
     */
    private String createObjectString(long objectID, int genID)
    {
        return Long.toString(objectID) + " " + Integer.toString(genID) + " obj";
    }

    /**
     * Brute force search for every object in the pdf.
     *
     * @throws IOException if something went wrong
     */
    private void bfSearchForObjects() throws IOException
    {
        if (bfSearchCOSObjectKeyOffsets == null)
        {
            bfSearchCOSObjectKeyOffsets = new HashMap<COSObjectKey, Long>();
            long originOffset = pdfSource.getPosition();
            long currentOffset = MINIMUM_SEARCH_OFFSET;
            String objString = " obj";
            char[] string = objString.toCharArray();
            do
            {
                pdfSource.seek(currentOffset);
                if (isString(string))
                {
                    long tempOffset = currentOffset - 1;
                    pdfSource.seek(tempOffset);
                    int genID = pdfSource.peek();
                    // is the next char a digit?
                    if (isDigit(genID))
                    {
                        genID -= 48;
                        tempOffset--;
                        pdfSource.seek(tempOffset);
                        if (isSpace())
                        {
                            while (tempOffset > MINIMUM_SEARCH_OFFSET && isSpace())
                            {
                                pdfSource.seek(--tempOffset);
                            }
                            int length = 0;
                            while (tempOffset > MINIMUM_SEARCH_OFFSET && isDigit())
                            {
                                pdfSource.seek(--tempOffset);
                                length++;
                            }
                            if (length > 0)
                            {
                                pdfSource.read();
                                byte[] objIDBytes = pdfSource.readFully(length);
                                String objIdString = new String(objIDBytes, 0,
                                        objIDBytes.length, ISO_8859_1);
                                Long objectID;
                                try
                                {
                                    objectID = Long.valueOf(objIdString);
                                }
                                catch (NumberFormatException exception)
                                {
                                    objectID = null;
                                }
                                if (objectID != null)
                                {
                                    bfSearchCOSObjectKeyOffsets.put(new COSObjectKey(objectID, genID), tempOffset+1);
                                }
                            }
                        }
                    }
                }
                currentOffset++;
            }
            while (!pdfSource.isEOF());
            // reestablish origin position
            pdfSource.seek(originOffset);
        }
    }

    /**
     * Search for the offset of the given xref table/stream among those found by a brute force search.
     *
     * @param streamsOnly search for xref streams only
     * @return the offset of the xref entry
     * @throws IOException if something went wrong
     */
    private long bfSearchForXRef(long xrefOffset, boolean streamsOnly) throws IOException
    {
        long newOffset = -1;
        long newOffsetTable = -1;
        long newOffsetStream = -1;
        if (!streamsOnly)
        {
            bfSearchForXRefTables();
        }
        bfSearchForXRefStreams();
        if (!streamsOnly && bfSearchXRefTablesOffsets != null)
        {
            // TODO to be optimized, this won't work in every case
            newOffsetTable = searchNearestValue(bfSearchXRefTablesOffsets, xrefOffset);
        }
        if (bfSearchXRefStreamsOffsets != null)
        {
            // TODO to be optimized, this won't work in every case
            newOffsetStream = searchNearestValue(bfSearchXRefStreamsOffsets, xrefOffset);
        }
        // choose the nearest value
        if (newOffsetTable > -1 && newOffsetStream > -1)
        {
            long differenceTable = xrefOffset - newOffsetTable;
            long differenceStream = xrefOffset - newOffsetStream;
            if (Math.abs(differenceTable) > Math.abs(differenceStream))
            {
                newOffset = differenceStream;
                bfSearchXRefStreamsOffsets.remove(newOffsetStream);
            }
            else
            {
                newOffset = differenceTable;
                bfSearchXRefTablesOffsets.remove(newOffsetTable);
            }
        }
        else if (newOffsetTable > -1)
        {
            newOffset = newOffsetTable;
            bfSearchXRefTablesOffsets.remove(newOffsetTable);
        }
        else if (newOffsetStream > -1)
        {
            newOffset = newOffsetStream;
            bfSearchXRefStreamsOffsets.remove(newOffsetStream);
        }
        return newOffset;
    }

    private long searchNearestValue(List<Long> values, long offset)
    {
        long newValue = -1;
        long currentDifference = -1;
        int currentOffsetIndex = -1;
        int numberOfOffsets = values.size();
        // find the nearest value
        for (int i = 0; i < numberOfOffsets; i++)
        {
            long newDifference = offset - values.get(i);
            // find the nearest offset
            if (currentDifference == -1
                    || (Math.abs(currentDifference) > Math.abs(newDifference)))
            {
                currentDifference = newDifference;
                currentOffsetIndex = i;
            }
        }
        if (currentOffsetIndex > -1)
        {
            newValue = values.get(currentOffsetIndex);
        }
        return newValue;
    }
    /**
     * Brute force search for all xref entries (tables).
     *
     * @throws IOException if something went wrong
     */
    private void bfSearchForXRefTables() throws IOException
    {
        if (bfSearchXRefTablesOffsets == null)
        {
            // a pdf may contain more than one xref entry
            bfSearchXRefTablesOffsets = new Vector<Long>();
            long originOffset = pdfSource.getPosition();
            pdfSource.seek(MINIMUM_SEARCH_OFFSET);
            // search for xref tables
            while (!pdfSource.isEOF())
            {
                if (isString(XREF_TABLE))
                {
                    long newOffset = pdfSource.getPosition();
                    pdfSource.seek(newOffset - 1);
                    // ensure that we don't read "startxref" instead of "xref"
                    if (isWhitespace())
                    {
                        bfSearchXRefTablesOffsets.add(newOffset);
                    }
                    pdfSource.seek(newOffset + 4);
                }
                pdfSource.read();
            }
            pdfSource.seek(originOffset);
        }
    }

    /**
     * Brute force search for all /XRef entries (streams).
     *
     * @throws IOException if something went wrong
     */
    private void bfSearchForXRefStreams() throws IOException
    {
        if (bfSearchXRefStreamsOffsets == null)
        {
            // a pdf may contain more than one /XRef entry
            bfSearchXRefStreamsOffsets = new Vector<Long>();
            long originOffset = pdfSource.getPosition();
            pdfSource.seek(MINIMUM_SEARCH_OFFSET);
            // search for XRef streams
            String objString = " obj";
            char[] string = objString.toCharArray();
            while (!pdfSource.isEOF())
            {
                if (isString(XREF_STREAM))
                {
                    // search backwards for the beginning of the stream
                    long newOffset = -1;
                    long xrefOffset = pdfSource.getPosition();
                    boolean objFound = false;
                    for (int i = 1; i < 30 && !objFound; i++)
                    {
                        long currentOffset = xrefOffset - (i * 10);
                        if (currentOffset > 0)
                        {
                            pdfSource.seek(currentOffset);
                            for (int j = 0; j < 10; j++)
                            {
                                if (isString(string))
                                {
                                    long tempOffset = currentOffset - 1;
                                    pdfSource.seek(tempOffset);
                                    int genID = pdfSource.peek();
                                    // is the next char a digit?
                                    if (isDigit(genID))
                                    {
                                        genID -= 48;
                                        tempOffset--;
                                        pdfSource.seek(tempOffset);
                                        if (isSpace())
                                        {
                                            int length = 0;
                                            pdfSource.seek(--tempOffset);
                                            while (tempOffset > MINIMUM_SEARCH_OFFSET && isDigit())
                                            {
                                                pdfSource.seek(--tempOffset);
                                                length++;
                                            }
                                            if (length > 0)
                                            {
                                                pdfSource.read();
                                                newOffset = pdfSource.getPosition();
                                            }
                                        }
                                    }
                                    LOG.debug("Fixed reference for xref stream " + xrefOffset
                                            + " -> " + newOffset);
                                    objFound = true;
                                    break;
                                }
                                else
                                {
                                    currentOffset++;
                                    pdfSource.read();
                                }
                            }
                        }
                    }
                    if (newOffset > -1)
                    {
                        bfSearchXRefStreamsOffsets.add(newOffset);
                    }
                    pdfSource.seek(xrefOffset + 5);
                }
                pdfSource.read();
            }
            pdfSource.seek(originOffset);
        }
    }

    /**
     * Rebuild the trailer dictionary if startxref can't be found.
     *
     * @return the rebuild trailer dictionary
     *
     * @throws IOException if something went wrong
     */
    protected final COSDictionary rebuildTrailer() throws IOException
    {
        COSDictionary trailer = null;
        bfSearchForObjects();
        if (bfSearchCOSObjectKeyOffsets != null)
        {
            xrefTrailerResolver.nextXrefObj(0, XRefType.TABLE);
            for (COSObjectKey objectKey : bfSearchCOSObjectKeyOffsets.keySet())
            {
                xrefTrailerResolver.setXRef(objectKey, bfSearchCOSObjectKeyOffsets.get(objectKey));
            }
            xrefTrailerResolver.setStartxref(0);
            trailer = xrefTrailerResolver.getTrailer();
            getDocument().setTrailer(trailer);
            // search for the different parts of the trailer dictionary 
            for(COSObjectKey key : bfSearchCOSObjectKeyOffsets.keySet())
            {
                Long offset = bfSearchCOSObjectKeyOffsets.get(key);
                pdfSource.seek(offset);
                readObjectNumber();
                readGenerationNumber();
                readExpectedString(OBJ_MARKER, true);
                try
                {
                    COSDictionary dictionary = parseCOSDictionary();
                    if (dictionary != null)
                    {
                        // document catalog
                        if (COSName.CATALOG.equals(dictionary.getCOSName(COSName.TYPE)))
                        {
                            trailer.setItem(COSName.ROOT, document.getObjectFromPool(key));
                        }
                        // info dictionary
                        else if (dictionary.containsKey(COSName.TITLE)
                                || dictionary.containsKey(COSName.AUTHOR)
                                || dictionary.containsKey(COSName.SUBJECT)
                                || dictionary.containsKey(COSName.KEYWORDS)
                                || dictionary.containsKey(COSName.CREATOR)
                                || dictionary.containsKey(COSName.PRODUCER)
                                || dictionary.containsKey(COSName.CREATION_DATE))
                        {
                            trailer.setItem(COSName.INFO, document.getObjectFromPool(key));
                        }
                        // TODO encryption dictionary
                    }
                }
                catch(IOException exception)
                {
                    LOG.debug("Skipped object "+key+", either it's corrupt or not a dictionary");
                }
            }
        }
        return trailer;
    }

    /**
     * This will parse the startxref section from the stream.
     * The startxref value is ignored.
     *
     * @return the startxref value or -1 on parsing error
     * @throws IOException If an IO error occurs.
     */
    private long parseStartXref() throws IOException
    {
        long startXref = -1;
        if (isString(STARTXREF))
        {
            readString();
            skipSpaces();
            // This integer is the byte offset of the first object referenced by the xref or xref stream
            startXref = readLong();
        }
        return startXref;
    }

    /**
     * Checks if the given string can be found at the current offset.
     *
     * @param string the bytes of the string to look for
     * @return true if the bytes are in place, false if not
     * @throws IOException if something went wrong
     */
    private boolean isString(byte[] string) throws IOException
    {
        boolean bytesMatching = false;
        if (pdfSource.peek() == string[0])
        {
            int length = string.length;
            byte[] bytesRead = new byte[length];
            int numberOfBytes = pdfSource.read(bytesRead, 0, length);
            while (numberOfBytes < length)
            {
                int readMore = pdfSource.read(bytesRead, numberOfBytes, length - numberOfBytes);
                if (readMore < 0)
                {
                    break;
                }
                numberOfBytes += readMore;
            }
            if (Arrays.equals(string, bytesRead))
            {
                bytesMatching = true;
            }
            pdfSource.rewind(numberOfBytes);
        }
        return bytesMatching;
    }

    private boolean isObjHeader(String expectedObjHeader) throws IOException {
        long objN = readObjectNumber();
        int genN = readGenerationNumber();
        //to ensure that we have "obj" keyword
        readExpectedString(OBJ_MARKER, true);
        String actualObjHeader = createObjectString(objN, genN);
        return actualObjHeader.equals(expectedObjHeader);
    }

    /**
     * Checks if the given string can be found at the current offset.
     *
     * @param string the bytes of the string to look for
     * @return true if the bytes are in place, false if not
     * @throws IOException if something went wrong
     */
    private boolean isString(char[] string) throws IOException
    {
        boolean bytesMatching = true;
        long originOffset = pdfSource.getPosition();
        for (char c : string)
        {
            if (pdfSource.read() != c)
            {
                bytesMatching = false;
            }
        }
        pdfSource.seek(originOffset);
        return bytesMatching;
    }

    /**
     * This will parse the trailer from the stream and add it to the state.
     *
     * @return false on parsing error
     * @throws IOException If an IO error occurs.
     */
    private boolean parseTrailer() throws IOException
    {
        if(pdfSource.peek() != 't')
        {
            return false;
        }
        //read "trailer"
        long currentOffset = pdfSource.getPosition();
        String nextLine = readLine();
        if( !nextLine.trim().equals( "trailer" ) )
        {
            // in some cases the EOL is missing and the trailer immediately
            // continues with "<<" or with a blank character
            // even if this does not comply with PDF reference we want to support as many PDFs as possible
            // Acrobat reader can also deal with this.
            if (nextLine.startsWith("trailer"))
            {
                // we can't just unread a portion of the read data as we don't know if the EOL consist of 1 or 2 bytes
                int len = "trailer".length();
                // jump back right after "trailer"
                pdfSource.seek(currentOffset + len);
            }
            else
            {
                return false;
            }
        }

        // in some cases the EOL is missing and the trailer continues with " <<"
        // even if this does not comply with PDF reference we want to support as many PDFs as possible
        // Acrobat reader can also deal with this.
        skipSpaces();

        COSDictionary parsedTrailer = parseCOSDictionary();
        xrefTrailerResolver.setTrailer( parsedTrailer );

        skipSpaces();
        return true;
    }

    /**
     * Parse the header of a pdf.
     *
     * @return true if a PDF header was found
     * @throws IOException if something went wrong
     */
    protected boolean parsePDFHeader() throws IOException
    {
        return parseHeader(PDF_HEADER, PDF_DEFAULT_VERSION);
    }

    /**
     * Parse the header of a fdf.
     *
     * @return true if a FDF header was found
     * @throws IOException if something went wrong
     */
    protected boolean parseFDFHeader() throws IOException
    {
        return parseHeader(FDF_HEADER, FDF_DEFAULT_VERSION);
    }

    private boolean parseHeader(String headerMarker, String defaultVersion) throws IOException
    {
        /*
            6.1.2 File header
            The % character of the file header shall occur at byte offset 0 of the file.
            The file header line shall be immediately followed by a comment consisting of a % character followed by at least four characters,
            each of whose encoded byte values shall have a decimal value greater than 127.
        */
        // read first line
        String header = readLine();
        // some pdf-documents are broken and the pdf-version is in one of the following lines
        if (!header.contains(headerMarker))
        {
            header = readLine();
            while (!header.contains(headerMarker) && !header.contains(headerMarker.substring(1)))
            {
                // if a line starts with a digit, it has to be the first one with data in it
                if ((header.length() > 0) && (Character.isDigit(header.charAt(0))))
                {
                    break;
                }
                header = readLine();
            }
        }

		do {
			this.pdfSource.rewind(1);
		} while (isEOL());
		this.pdfSource.read();

		final int headerStart = header.indexOf(headerMarker);
		final long headerOffset = this.pdfSource.getPosition() - header.length() + headerStart;

		this.document.setHeaderOffset(headerOffset);
		this.document.setHeader(header);

		skipWhiteSpaces();

		// nothing found
        if (!header.contains(headerMarker))
        {
            pdfSource.seek(0);
            //false mean that case PDF-1.4 (without percentage) generate IOException
			if (!validationParsing) {
				return false;
			}
        }

        // greater than zero because if it is zero then there is no point of trimming
        if ( headerStart > 0 )
        {
            //trim off any leading characters
            header = header.substring( headerStart, header.length() );
        }

        // This is used if there is garbage after the header on the same line
        if (header.startsWith(headerMarker) && !header.matches(headerMarker + "\\d.\\d"))
        {
			if (header.length() < headerMarker.length() + 3) {
				// No version number at all, set to 1.4 as default
				header = headerMarker + defaultVersion;
				LOG.debug("No version found, set to " + defaultVersion + " as default.");
			} else if (validationParsing) {
				// trying to parse header version if it has some garbage
				Integer pos = null;
				if (header.indexOf(37) > -1) {
					pos = Integer.valueOf(header.indexOf(37));
				} else if (header.contains("PDF-")) {
					pos = Integer.valueOf(header.indexOf("PDF-"));
				}
				if (pos != null) {
					Integer length = Math.min(8, header.substring(pos).length());
					header = header.substring(pos, pos + length);
				}
			} else {
				String headerGarbage = header.substring(headerMarker.length() + 3, header.length()) + "\n";
				header = header.substring(0, headerMarker.length() + 3);
				pdfSource.rewind(headerGarbage.getBytes(ISO_8859_1).length);
			}
		}
		float headerVersion = 1.4f;
        try
        {
            String[] headerParts = header.split("-");
            if (headerParts.length == 2)
            {
                headerVersion = Float.parseFloat(headerParts[1]);
            }
        }
        catch (NumberFormatException exception)
        {
            LOG.debug("Can't parse the header version.", exception);
        }
        this.document.setVersion(headerVersion);
        if (this.validationParsing) {
            this.checkComment();
        }
        // rewind
        this.pdfSource.seek(0);
        return true;
    }

    /** check second line of pdf header
     */
    private void checkComment() throws IOException {
		String comment = readLine();
		boolean isValidComment = true;

		if (comment != null && !comment.isEmpty()) {
			if (comment.charAt(0) != '%') {
				isValidComment = false;
			}

			int pos = comment.indexOf('%') > -1 ? comment.indexOf('%') + 1 : 0;
			if (comment.substring(pos).length() < 4) {
				isValidComment = false;
			}
		} else {
			isValidComment = false;
		}
		if (isValidComment) {
			setBinaryHeaderBytes(comment.charAt(1), comment.charAt(2),
					comment.charAt(3), comment.charAt(4));
		} else {
			setBinaryHeaderBytes(-1, -1, -1, -1);
		}
    }

	private void setBinaryHeaderBytes(int first, int second, int third, int fourth) {
		this.document.setHeaderCommentByte1(first);
		this.document.setHeaderCommentByte2(second);
		this.document.setHeaderCommentByte3(third);
		this.document.setHeaderCommentByte4(fourth);
	}

    /**
     * This will parse the xref table from the stream and add it to the state
     * The XrefTable contents are ignored.
     * @param startByteOffset the offset to start at
     * @return false on parsing error
     * @throws IOException If an IO error occurs.
     */
    protected boolean parseXrefTable(long startByteOffset) throws IOException
    {
        if(pdfSource.peek() != 'x')
        {
            return false;
        }
        String xref = readString();
        if( !xref.trim().equals( "xref" ) )
        {
            return false;
        }

        //check spacings after "xref" keyword
        //pdf/a-1b specification, clause 6.1.4
        int space;
        if (validationParsing) {
            space = pdfSource.read();
			if (space == 0x0D) {
				if (pdfSource.peek() == 0x0A) {
					pdfSource.read();
				}
				if (!isDigit()) {
					document.setXrefEOLMarkersComplyPDFA(Boolean.FALSE);
				}
			} else if (space != 0x0A || !isDigit()) {
				document.setXrefEOLMarkersComplyPDFA(Boolean.FALSE);
			}
        }
        // check for trailer after xref
        String str = readString();
        byte[] b = str.getBytes(ISO_8859_1);
        pdfSource.rewind(b.length);
        
        // signal start of new XRef
        xrefTrailerResolver.nextXrefObj( startByteOffset, XRefType.TABLE );

        if (str.startsWith("trailer"))
        {
            LOG.debug("skipping empty xref table");
            return false;
        }

        // Xref tables can have multiple sections. Each starts with a starting object id and a count.
        while(true)
        {
            // first obj id
            long currObjID = readObjectNumber();

            //check spacings between header elements
            //pdf/a-1b specification, clause 6.1.4
            if (validationParsing) {
                space = pdfSource.read();
                if (space != 0x20 || !isDigit()) {
                    document.setSubsectionHeaderSpaceSeparated(Boolean.FALSE);
                }
            }

            // the number of objects in the xref table
            long count = readLong();

            skipSpaces();
            for(int i = 0; i < count; i++)
            {
                if(pdfSource.isEOF() || isEndOfName((char)pdfSource.peek()))
                {
                    break;
                }
                if(pdfSource.peek() == 't')
                {
                    break;
                }
                //Ignore table contents
                String currentLine = readLine();
                String[] splitString = currentLine.split("\\s");
                if (splitString.length < 3)
                {
                    LOG.debug("invalid xref line: " + currentLine);
                    break;
                }
                /* This supports the corrupt table as reported in
                 * PDFBOX-474 (XXXX XXX XX n) */
                if(splitString[splitString.length-1].equals("n"))
                {
                    try
                    {
                        int currOffset = Integer.parseInt(splitString[0]);
                        int currGenID = Integer.parseInt(splitString[1]);
                        COSObjectKey objKey = new COSObjectKey(currObjID, currGenID);
                        xrefTrailerResolver.setXRef(objKey, currOffset);
                    }
                    catch(NumberFormatException e)
                    {
                        throw new IOException(e);
                    }
                }
                else if(!splitString[2].equals("f"))
                {
                    throw new IOException("Corrupt XRefTable Entry - ObjID:" + currObjID);
                }
                currObjID++;
                skipSpaces();
            }
            skipSpaces();
            if (!isDigit())
            {
                break;
            }
        }
        return true;
    }

    /**
     * Fills XRefTrailerResolver with data of given stream.
     * Stream must be of type XRef.
     * @param stream the stream to be read
     * @param objByteOffset the offset to start at
     * @param isStandalone should be set to true if the stream is not part of a hybrid xref table
     * @throws IOException if there is an error parsing the stream
     */
    private void parseXrefStream(COSStream stream, long objByteOffset, boolean isStandalone) throws IOException
    {
        // the cross reference stream of a hybrid xref table will be added to the existing one
        // and we must not override the offset and the trailer
        if ( isStandalone )
        {
            xrefTrailerResolver.nextXrefObj( objByteOffset, XRefType.STREAM );
            xrefTrailerResolver.setTrailer( stream );
        }
        PDFXrefStreamParser parser = new PDFXrefStreamParser( stream, document, xrefTrailerResolver );
        parser.parse();
    }

    /**
     * This will get the document that was parsed.  parse() must be called before this is called.
     * When you are done with this document you must call close() on it to release
     * resources.
     *
     * @return The document that was parsed.
     *
     * @throws IOException If there is an error getting the document.
     */
    public COSDocument getDocument() throws IOException
    {
        if( document == null )
        {
            throw new IOException( "You must call parse() before calling getDocument()" );
        }
        return document;
    }

    /**
     * Parse the values of the trailer dictionary and return the root object.
     *
     * @param trailer The trailer dictionary.
     * @return The parsed root object.
     * @throws IOException If an IO error occurs or if the root object is
     * missing in the trailer dictionary.
     */
    protected COSBase parseTrailerValuesDynamically(COSDictionary trailer) throws IOException
    {
        // PDFBOX-1557 - ensure that all COSObject are loaded in the trailer
        // PDFBOX-1606 - after securityHandler has been instantiated
        for (COSBase trailerEntry : trailer.getValues())
        {
            if (trailerEntry instanceof COSObject)
            {
                COSObject tmpObj = (COSObject) trailerEntry;
                parseObjectDynamically(tmpObj, false);
            }
        }
        // parse catalog or root object
        COSObject root = (COSObject) trailer.getItem(COSName.ROOT);
        if (root == null)
        {
            throw new IOException("Missing root object specification in trailer.");
        }
        return parseObjectDynamically(root, false);
    }

    public COSDictionary getFirstTrailer() {
        return xrefTrailerResolver.getFirstTrailer();
    }

    /**
     * @return last trailer in current document
     */
    public COSDictionary getLastTrailer() {
        return xrefTrailerResolver.getLastTrailer();
    }

    protected void isLinearized(Long fileLen) throws IOException {
        Map.Entry<COSObjectKey, Long> object = getFirstDictionary();

        final COSObject pdfObject = new COSObject(null);
        if (object != null) {
            parseFileObject(object.getValue(),
                    object.getKey(),
                    object.getKey().getNumber(),
                    object.getKey().getGeneration(),
                    pdfObject);
        } else {
            LOG.debug("Linearization dictionary is missed in document");
            return ;
        }

        if (pdfObject.getObject() != null && pdfObject.getObject() instanceof COSDictionary) {
            final COSDictionary linearized = (COSDictionary) pdfObject.getObject();
            if (linearized.getItem(COSName.getPDFName("Linearized")) != null) {
                COSNumber length = (COSNumber) linearized.getItem(COSName.L);
                if (length != null) {
                    Boolean isLinearized = Boolean.valueOf(length.longValue() == fileLen.longValue()
                                            && pdfSource.getPosition() < LINEARIZATION_SIZE);
                    document.setIsLinearized(isLinearized);
                }
            }
        }
    }

    private Entry<COSObjectKey, Long> getFirstDictionary() throws IOException {
        pdfSource.seek(0L);
        skipSpaces();
        final int bound = Math.min(pdfSource.available(), LINEARIZATION_SIZE);

        for (Long offset = Long.valueOf(pdfSource.getPosition()); offset < bound; offset++) {
            try {
                pdfSource.seek(offset);
                Long objNr = readObjectNumber();
                Integer genNr = readGenerationNumber();
                readExpectedString(OBJ_MARKER, Boolean.TRUE);
                return new AbstractMap.SimpleEntry<COSObjectKey, Long>(new COSObjectKey(objNr, genNr), offset);
            } catch (IOException ignore) {
				// if we`ve got trash instead of object or generation number, or 'obj' marker,
				// than we try to get it on next position
			}
        }
        return null;
    }

    /**
     * This will get the encryption dictionary. The document must be parsed before this is called.
     *
     * @return The encryption dictionary of the document that was parsed.
     *
     * @throws IOException If there is an error getting the document.
     */
    public PDEncryption getEncryption() throws IOException
    {
        if (document == null)
        {
            throw new IOException(
                    "You must parse the document first before calling getEncryption()");
        }
        return encryption;
    }

    public AccessPermission getAccessPermission() throws IOException
    {
        if (document == null)
        {
            throw new IOException(
                    "You must parse the document first before calling getAccessPermission()");
        }
        return accessPermission;
    }
}
