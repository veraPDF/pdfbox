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
import org.apache.pdfbox.io.RandomAccessRead;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.apache.pdfbox.util.Charsets.ISO_8859_1;

/**
 * This class is used to contain parsing logic that will be used by both the
 * PDFParser and the COSStreamParser.
 *
 * @author Ben Litchfield
 */
public abstract class BaseParser
{

    private static final long OBJECT_NUMBER_THRESHOLD = 10000000000L;

    private static final long GENERATION_NUMBER_THRESHOLD = 65535;
    
    /**
     * system property allowing to define size of push back buffer.
     */
    public static final String PROP_PUSHBACK_SIZE = "org.apache.pdfbox.baseParser.pushBackSize";

    /**
     * Log instance.
     */
    private static final Log LOG = LogFactory.getLog(BaseParser.class);

    protected static final int E = 'e';
    protected static final int N = 'n';
    protected static final int D = 'd';

    protected static final int S = 's';
    protected static final int T = 't';
    protected static final int R = 'r';
    protected static final int A = 'a';
    protected static final int M = 'm';

    protected static final int O = 'o';
    protected static final int B = 'b';
    protected static final int J = 'j';

    /**
     * This is a string constant that will be used for comparisons.
     */
    public static final String DEF = "def";
    /**
     * This is a string constant that will be used for comparisons.
     */
    protected static final String ENDOBJ_STRING = "endobj";
    /**
     * This is a string constant that will be used for comparisons.
     */
    protected static final String ENDSTREAM_STRING = "endstream";
    /**
     * This is a string constant that will be used for comparisons.
     */
    protected static final String STREAM_STRING = "stream";
    /**
     * This is a string constant that will be used for comparisons.
     */
    private static final String TRUE = "true";
    /**
     * This is a string constant that will be used for comparisons.
     */
    private static final String FALSE = "false";
    /**
     * This is a string constant that will be used for comparisons.
     */
    private static final String NULL = "null";

    private static final byte [] EOF_STRING = "%%EOF".getBytes();

    /**
     * ASCII code for line feed.
     */
    protected static final byte ASCII_LF = 10;
    /**
     * ASCII code for carriage return.
     */
    protected static final byte ASCII_CR = 13;
    private static final byte ASCII_ZERO = 48;
    private static final byte ASCII_NINE = 57;
    private static final byte ASCII_SPACE = 32;

    /**
     * When true pdfbox parses the document without all auto-healing methods
     */
    protected boolean validationParsing = false;

    /**
     * This is the stream that will be read from.
     */
    protected RandomAccessRead pdfSource;

    /**
     * This is the document that will be parsed.
     */
    protected COSDocument document;

	/**
     * These are structures that contain digital signatures and their byte
     * ranges.
     */
    private List<ByteRangeValidationStructure> byteRangeValidationStructures
            = new ArrayList<ByteRangeValidationStructure>();
    /**
     * Default constructor.
     */
    public BaseParser()
    {
    }

    /**
     * Constructor.
     *
     * @param stream The COS stream to read the data from.
     * @throws IOException If there is an error reading the input stream.
     */
    public BaseParser(COSStream stream) throws IOException
    {
        pdfSource = stream.getUnfilteredRandomAccess();
    }
    
    /**
     * Constructor.
     *
     * @param input The random access read to read the data from.
     * @throws IOException If there is an error reading the input stream.
     */
    public BaseParser(RandomAccessRead input) throws IOException
    {
        pdfSource = input;
    }

    private static boolean isHexDigit(char ch)
    {
        return isDigit(ch) ||
        (ch >= 'a' && ch <= 'f') ||
        (ch >= 'A' && ch <= 'F');
    }

    /**
     * This will parse a PDF dictionary value.
     *
     * @return The parsed Dictionary object.
     *
     * @throws IOException If there is an error parsing the dictionary object.
     */
    private COSBase parseCOSDictionaryValue() throws IOException
    {
        long numOffset = pdfSource.getPosition();
        COSBase number = parseDirObject();
        skipSpaces();
        if (!isDigit())
        {
            return number;
        }
        long genOffset = pdfSource.getPosition();
        COSBase generationNumber = parseDirObject();
        skipSpaces();
        readExpectedChar('R');
        if (!(number instanceof COSInteger))
        {
            throw new IOException("expected number, actual=" + number + " at offset " + numOffset);
        }
        if (!(generationNumber instanceof COSInteger))
        {
            throw new IOException("expected number, actual=" + number + " at offset " + genOffset);
        }
        COSObjectKey key = new COSObjectKey(((COSInteger) number).longValue(),
                ((COSInteger) generationNumber).intValue());
        return getObjectFromPool(key);
    }

    private COSBase parseSignatureDictionaryValue(ByteRangeValidationStructure structure)
            throws IOException
    {
        skipSpaces();
        long numOffset1 = pdfSource.getPosition();
        COSBase number = parseDirObject();
        long numOffset2 = pdfSource.getPosition();
        skipSpaces();
        if (!isDigit()) {
            structure.setContentsBeginningOffset(numOffset1);
            structure.setContentsEndingOffset(numOffset2);
            return number;
        }
        long genOffset = pdfSource.getPosition();
        COSBase generationNumber = parseDirObject();
        skipSpaces();
        readExpectedChar('R');
        if (!(number instanceof COSInteger)) {
            throw new IOException("expected number, actual=" + number + " at offset " + numOffset1);
        }
        if (!(generationNumber instanceof COSInteger)) {
            throw new IOException("expected number, actual=" + number + " at offset " + genOffset);
        }
        COSObjectKey key = new COSObjectKey(((COSInteger) number).longValue(),
                ((COSInteger) generationNumber).intValue());
        structure.setIndirectReference(key);
        return getObjectFromPool(key);
    }

    private COSBase getObjectFromPool(COSObjectKey key) throws IOException
    {
        if (document == null)
        {
            throw new IOException("object reference " + key + " at offset " + pdfSource.getPosition()
                    + " in content stream");
        }
        return document.getObjectFromPool(key);
    }

    /**
     * This will parse a PDF dictionary.
     *
     * @return The parsed dictionary.
     *
     * @throws IOException If there is an error reading the stream.
     */
    protected COSDictionary parseCOSDictionary() throws IOException
    {
        readExpectedChar('<');
        readExpectedChar('<');
        skipSpaces();
        COSDictionary obj = new COSDictionary();
        ByteRangeValidationStructure structure =
                new ByteRangeValidationStructure(obj);
        boolean done = false;
        while (!done)
        {
            skipSpaces();
            char c = (char) pdfSource.peek();
            if (c == '>')
            {
                done = true;
            }
            else if (c == '/')
            {
                parseCOSDictionaryNameValuePair(obj, structure);
            }
            else
            {
                // invalid dictionary, we were expecting a /Name, read until the end or until we can recover
                LOG.warn("Invalid dictionary, found: '" + c + "' but expected: '/'");
                if (readUntilEndOfCOSDictionary())
                {
                    // we couldn't recover
                    return obj;
                }
            }
        }
        readExpectedChar('>');
        readExpectedChar('>');
        if(structure.isSignature()) {
            structure.setFirstEofOffset(getOffsetOfNextEOF(pdfSource.getPosition()));
            byteRangeValidationStructures.add(structure);
        }
        return obj;
    }

    /**
     * Keep reading until the end of the dictionary object or the file has been hit, or until a '/'
     * has been found.
     *
     * @return true if the end of the object or the file has been found, false if not, i.e. that the
     * caller can continue to parse the dictionary at the current position.
     *
     * @throws IOException if there is a reading error.
     */
    private boolean readUntilEndOfCOSDictionary() throws IOException
    {
        int c = pdfSource.read();
        while (c != -1 && c != '/' && c != '>')
        {
            // in addition to stopping when we find / or >, we also want
            // to stop when we find endstream or endobj.
            if (c == E)
            {
                c = pdfSource.read();
                if (c == N)
                {
                    c = pdfSource.read();
                    if (c == D)
                    {
                        c = pdfSource.read();
                        boolean isStream = c == S && pdfSource.read() == T && pdfSource.read() == R
                                && pdfSource.read() == E && pdfSource.read() == A && pdfSource.read() == M;
                        boolean isObj = !isStream && c == O && pdfSource.read() == B && pdfSource.read() == J;
                        if (isStream || isObj)
                        {
                            // we're done reading this object!
                            return true;
                        }
                    }
                }
            }
            c = pdfSource.read();
        }
        if (c == -1)
        {
            return true;
        }
        pdfSource.rewind(1);
        return false;
    }

    private void parseCOSDictionaryNameValuePair(COSDictionary obj,
                                                 ByteRangeValidationStructure structure) throws IOException
    {
        COSName key = parseCOSName();
        COSBase value;
        if(key.compareTo(COSName.CONTENTS) != 0) {
            value = parseCOSDictionaryValue();
        } else {
            value = parseSignatureDictionaryValue(structure);
        }

        skipSpaces();
        if (((char) pdfSource.peek()) == 'd')
        {
            // if the next string is 'def' then we are parsing a cmap stream
            // and want to ignore it, otherwise throw an exception.
            String potentialDEF = readString();
            if (!potentialDEF.equals(DEF))
            {
                pdfSource.rewind(potentialDEF.getBytes(ISO_8859_1).length);
            }
            else
            {
                skipSpaces();
            }
        }

        if (value == null)
        {
            LOG.warn("Bad Dictionary Declaration " + pdfSource);
        }
        else
        {
            value.setDirect(true);
            obj.setItem(key, value);
        }
    }

    protected void skipWhiteSpaces() throws IOException
    {
        //PDF Ref 3.2.7 A stream must be followed by either
        //a CRLF or LF but nothing else.

        int whitespace = pdfSource.read();

        //see brother_scan_cover.pdf, it adds whitespaces
        //after the stream but before the start of the
        //data, so just read those first
        while (ASCII_SPACE == whitespace)
        {
            whitespace = pdfSource.read();
        }

        if (ASCII_CR == whitespace)
        {
            whitespace = pdfSource.read();
            if (ASCII_LF != whitespace)
            {
                pdfSource.rewind(1);
                //The spec says this is invalid but it happens in the real
                //world so we must support it.
            }
        }
        else if (ASCII_LF != whitespace)
        {
            //we are in an error.
            //but again we will do a lenient parsing and just assume that everything
            //is fine
            pdfSource.rewind(1);
        }
    }

    /**
     * This is really a bug in the Document creators code, but it caused a crash
     * in PDFBox, the first bug was in this format:
     * /Title ( (5)
     * /Creator which was patched in 1 place.
     * However it missed the case where the Close Paren was escaped
     *
     * The second bug was in this format
     * /Title (c:\)
     * /Producer
     *
     * This patch  moves this code out of the parseCOSString method, so it can be used twice.
     *
     *
     * @param bracesParameter the number of braces currently open.
     *
     * @return the corrected value of the brace counter
     * @throws IOException
     */
    private int checkForMissingCloseParen(final int bracesParameter) throws IOException
    {
        int braces = bracesParameter;
        byte[] nextThreeBytes = new byte[3];
        int amountRead = pdfSource.read(nextThreeBytes);

        //lets handle the special case seen in Bull  River Rules and Regulations.pdf
        //The dictionary looks like this
        //    2 0 obj
        //    <<
        //        /Type /Info
        //        /Creator (PaperPort http://www.scansoft.com)
        //        /Producer (sspdflib 1.0 http://www.scansoft.com)
        //        /Title ( (5)
        //        /Author ()
        //        /Subject ()
        //
        // Notice the /Title, the braces are not even but they should
        // be.  So lets assume that if we encounter an this scenario
        //   <end_brace><new_line><opening_slash> then that
        // means that there is an error in the pdf and assume that
        // was the end of the document.
        //
        if (amountRead == 3 &&
               (( nextThreeBytes[0] == ASCII_CR  // Look for a carriage return
               && nextThreeBytes[1] == ASCII_LF  // Look for a new line
               && nextThreeBytes[2] == 0x2f ) // Look for a slash /
                                              // Add a second case without a new line
               || (nextThreeBytes[0] == ASCII_CR  // Look for a carriage return
                && nextThreeBytes[1] == 0x2f )))  // Look for a slash /
            {
                braces = 0;
            }
        if (amountRead > 0)
        {
            pdfSource.rewind(amountRead);
        }
        return braces;
    }

    /**
     * This will parse a PDF string.
     *
     * @return The parsed PDF string.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    protected COSString parseCOSString() throws IOException
    {
        char nextChar = (char)pdfSource.read();
        char openBrace;
        char closeBrace;
        if( nextChar == '(' )
        {
            openBrace = '(';
            closeBrace = ')';
        }
        else if( nextChar == '<' )
        {
            return parseCOSHexString();
        }
        else
        {
            throw new IOException( "parseCOSString string should start with '(' or '<' and not '" +
                    nextChar + "' " + pdfSource );
        }
        
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        //This is the number of braces read
        //
        int braces = 1;
        int c = pdfSource.read();
        while( braces > 0 && c != -1)
        {
            char ch = (char)c;
            int nextc = -2; // not yet read

            if(ch == closeBrace)
            {

                braces--;
                braces = checkForMissingCloseParen(braces);
                if( braces != 0 )
                {
                    out.write(ch);
                }
            }
            else if( ch == openBrace )
            {
                braces++;
                out.write(ch);
            }
            else if( ch == '\\' )
            {
                //patched by ram
                char next = (char)pdfSource.read();
                switch(next)
                {
                    case 'n':
                        out.write('\n');
                        break;
                    case 'r':
                        out.write('\r');
                        break;
                    case 't':
                        out.write('\t');
                        break;
                    case 'b':
                        out.write('\b');
                        break;
                    case 'f':
                        out.write('\f');
                        break;
                    case ')':
                        // PDFBox 276 /Title (c:\)
                        braces = checkForMissingCloseParen(braces);
                        if( braces != 0 )
                        {
                            out.write(next);
                        }
                        else
                        {
                            out.write('\\');
                        }
                        break;
                    case '(':
                    case '\\':
                        out.write(next);
                        break;
                    case ASCII_LF:
                    case ASCII_CR:
                        //this is a break in the line so ignore it and the newline and continue
                        c = pdfSource.read();
                        while( isEOL(c) && c != -1)
                        {
                            c = pdfSource.read();
                        }
                        nextc = c;
                        break;
                    case '0':
                    case '1':
                    case '2':
                    case '3':
                    case '4':
                    case '5':
                    case '6':
                    case '7':
                    {
                        StringBuffer octal = new StringBuffer();
                        octal.append( next );
                        c = pdfSource.read();
                        char digit = (char)c;
                        if( digit >= '0' && digit <= '7' )
                        {
                            octal.append( digit );
                            c = pdfSource.read();
                            digit = (char)c;
                            if( digit >= '0' && digit <= '7' )
                            {
                                octal.append( digit );
                            }
                            else
                            {
                                nextc = c;
                            }
                        }
                        else
                        {
                            nextc = c;
                        }
    
                        int character = 0;
                        try
                        {
                            character = Integer.parseInt( octal.toString(), 8 );
                        }
                        catch( NumberFormatException e )
                        {
                            throw new IOException( "Error: Expected octal character, actual='" + octal + "'", e );
                        }
                        out.write(character);
                        break;
                    }
                    default:
                    {
                        // dropping the backslash
                        // see 7.3.4.2 Literal Strings for further information
                        out.write(next);
                    }
                }
            }
            else
            {
                out.write(ch);
            }
            if (nextc != -2)
            {
                c = nextc;
            }
            else
            {
                c = pdfSource.read();
            }
        }
        if (c != -1)
        {
            pdfSource.rewind(1);
        }
        return new COSString(out.toByteArray());
    }

    /**
     * This will parse a PDF HEX string with fail fast semantic
     * meaning that we stop if a not allowed character is found.
     * This is necessary in order to detect malformed input and
     * be able to skip to next object start.
     *
     * We assume starting '&lt;' was already read.
     * 
     * @return The parsed PDF string.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    private COSString parseCOSHexString() throws IOException
    {
        if (validationParsing) {
            return validationParseCOSHexString();
        } else {
            final StringBuilder sBuf = new StringBuilder();
            while( true )
            {
                int c = pdfSource.read();
                if ( isHexDigit((char)c) )
                {
                    sBuf.append( (char) c );
                }
                else if ( c == '>' )
                {
                    break;
                }
                else if ( c < 0 )
                {
                    throw new IOException( "Missing closing bracket for hex string. Reached EOS." );
                }
                else if ( ( c == ' ' ) || ( c == '\n' ) ||
                        ( c == '\t' ) || ( c == '\r' ) ||
                        ( c == '\b' ) || ( c == '\f' ) )
                {
                    continue;
                }
                else
                {
                    // if invalid chars was found: discard last
                    // hex character if it is not part of a pair
                    if (sBuf.length()%2!=0)
                    {
                        sBuf.deleteCharAt(sBuf.length()-1);
                    }

                    // read till the closing bracket was found
                    do
                    {
                        c = pdfSource.read();
                    }
                    while ( c != '>' && c >= 0 );

                    // might have reached EOF while looking for the closing bracket
                    // this can happen for malformed PDFs only. Make sure that there is
                    // no endless loop.
                    if ( c < 0 )
                    {
                        throw new IOException( "Missing closing bracket for hex string. Reached EOS." );
                    }

                    // exit loop
                    break;
                }
            }
            return COSString.parseHex(sBuf.toString());
        }
    }

    // during this parsing we count hex characters and check for invalid ones
    // pdf/a-1b specification, clause 6.1.6
    private COSString validationParseCOSHexString() throws IOException {
        Boolean isHexSymbols = Boolean.TRUE;
        Long hexCount = Long.valueOf(0);

        final StringBuilder sBuf = new StringBuilder();
        while (true) {
            int c = pdfSource.read();
            if (isHexDigit((char) c)) {
                sBuf.append((char) c);
                hexCount++;
            } else if (c == '>') {
                break;
            } else if (c < 0) {
                throw new IOException("Missing closing bracket for hex string. Reached EOS.");
            } else if (isWhitespace(c)) {
                continue;
            } else {
                isHexSymbols = Boolean.FALSE;
                hexCount++;
            }
        }
        COSString result = COSString.parseHex(sBuf.toString());
        result.setHexCount(hexCount);
        result.setContainsOnlyHex(isHexSymbols);

        return result;
    }

    /**
     * This will parse a PDF array object.
     *
     * @return The parsed PDF array.
     *
     * @throws IOException If there is an error parsing the stream.
     */
    protected COSArray parseCOSArray() throws IOException
    {
        readExpectedChar('[');
        COSArray po = new COSArray();
        COSBase pbo;
        skipSpaces();
        int i;
        while( ((i = pdfSource.peek()) > 0) && ((char)i != ']') )
        {
            pbo = parseDirObject();
            if( pbo instanceof COSObject )
            {
                // We have to check if the expected values are there or not PDFBOX-385
                if (po.get(po.size()-1) instanceof COSInteger)
                {
                    COSInteger genNumber = (COSInteger)po.remove( po.size() -1 );
                    if (po.get(po.size()-1) instanceof COSInteger)
                    {
                        COSInteger number = (COSInteger)po.remove( po.size() -1 );
                        COSObjectKey key = new COSObjectKey(number.longValue(), genNumber.intValue());
                        pbo = getObjectFromPool(key);
                    }
                    else
                    {
                        // the object reference is somehow wrong
                        pbo = null;
                    }
                }
                else
                {
                    pbo = null;
                }
            }
            if( pbo != null )
            {
                po.add( pbo );
            }
            else
            {
                //it could be a bad object in the array which is just skipped
                LOG.warn("Corrupt object reference at offset " + pdfSource.getPosition());

                // This could also be an "endobj" or "endstream" which means we can assume that
                // the array has ended.
                String isThisTheEnd = readString();
                pdfSource.rewind(isThisTheEnd.getBytes(ISO_8859_1).length);
                if(ENDOBJ_STRING.equals(isThisTheEnd) || ENDSTREAM_STRING.equals(isThisTheEnd))
                {
                    return po;
                }
            }
            skipSpaces();
        }
        // read ']'
        pdfSource.read(); 
        skipSpaces();
        return po;
    }

    /**
     * Determine if a character terminates a PDF name.
     *
     * @param ch The character
     * @return true if the character terminates a PDF name, otherwise false.
     */
    protected boolean isEndOfName(int ch)
    {
        return ch == ASCII_SPACE || ch == ASCII_CR || ch == ASCII_LF || ch == 9 || ch == '>' ||
               ch == '<' || ch == '[' || ch =='/' || ch ==']' || ch ==')' || ch =='(';
    }

    /**
     * This will parse a PDF name from the stream.
     *
     * @return The parsed PDF name.
     * @throws IOException If there is an error reading from the stream.
     */
    protected COSName parseCOSName() throws IOException
    {
        readExpectedChar('/');
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		// we are get internal representation of name for support implementation limits
		// pdf reference 1.4, appendix c
        int c = pdfSource.read();
        while (c != -1)
        {
            int ch = c;
            if (ch == '#')
            {
                char ch1 = (char)pdfSource.read();
                char ch2 = (char)pdfSource.read();

                // Prior to PDF v1.2, the # was not a special character.  Also,
                // it has been observed that various PDF tools do not follow the
                // spec with respect to the # escape, even though they report
                // PDF versions of 1.2 or later.  The solution here is that we
                // interpret the # as an escape only when it is followed by two
                // valid hex digits.
                if (isHexDigit(ch1) && isHexDigit(ch2))
                {
                    String hex = "" + ch1 + ch2;
                    try
                    {
                        buffer.write(Integer.parseInt(hex, 16));
                    }
                    catch (NumberFormatException e)
                    {
                        throw new IOException("Error: expected hex digit, actual='" + hex + "'", e);
                    }
                    c = pdfSource.read();
                }
                else
                {
                    pdfSource.rewind(1);
                    c = ch1;
                    buffer.write(ch);
                }
            }
            else if (isEndOfName(ch))
            {
                break;
            }
            else
            {
                buffer.write(ch);
                c = pdfSource.read();
            }
        }
        if (c != -1)
        {
            pdfSource.rewind(1);
        }
        String string = new String(buffer.toByteArray());
        return COSName.getPDFName(string);
    }

    /**
     * This will parse a boolean object from the stream.
     *
     * @return The parsed boolean object.
     *
     * @throws IOException If an IO error occurs during parsing.
     */
    protected COSBoolean parseBoolean() throws IOException
    {
        COSBoolean retval = null;
        char c = (char)pdfSource.peek();
        if( c == 't' )
        {
            String trueString = new String( pdfSource.readFully( 4 ), ISO_8859_1 );
            if( !trueString.equals( TRUE ) )
            {
                throw new IOException( "Error parsing boolean: expected='true' actual='" + trueString 
                        + "' at offset " + pdfSource.getPosition());
            }
            else
            {
                retval = COSBoolean.TRUE;
            }
        }
        else if( c == 'f' )
        {
            String falseString = new String( pdfSource.readFully( 5 ), ISO_8859_1 );
            if( !falseString.equals( FALSE ) )
            {
                throw new IOException( "Error parsing boolean: expected='true' actual='" + falseString 
                        + "' at offset " + pdfSource.getPosition());
            }
            else
            {
                retval = COSBoolean.FALSE;
            }
        }
        else
        {
            throw new IOException( "Error parsing boolean expected='t or f' actual='" + c 
                    + "' at offset " + pdfSource.getPosition());
        }
        return retval;
    }

    /**
     * This will parse a directory object from the stream.
     *
     * @return The parsed object.
     *
     * @throws IOException If there is an error during parsing.
     */
    protected COSBase parseDirObject() throws IOException
    {
        COSBase retval = null;

        skipSpaces();
        int nextByte = pdfSource.peek();
        char c = (char)nextByte;
        switch(c)
        {
        case '<':
        {
            // pull off first left bracket
            int leftBracket = pdfSource.read();
            // check for second left bracket
            c = (char)pdfSource.peek(); 
            pdfSource.rewind(1);
            if(c == '<')
            {

                retval = parseCOSDictionary();
                skipSpaces();
            }
            else
            {
                retval = parseCOSString();
            }
            break;
        }
        case '[':
        {
            // array
            retval = parseCOSArray();
            break;
        }
        case '(':
            retval = parseCOSString();
            break;
        case '/':   
            // name
            retval = parseCOSName();
            break;
        case 'n':   
        {
            // null
            readExpectedString(NULL);
            retval = COSNull.NULL;
            break;
        }
        case 't':
        {
            String trueString = new String( pdfSource.readFully(4), ISO_8859_1 );
            if( trueString.equals( TRUE ) )
            {
                retval = COSBoolean.TRUE;
            }
            else
            {
                throw new IOException( "expected true actual='" + trueString + "' " + pdfSource + 
                        "' at offset " + pdfSource.getPosition());
            }
            break;
        }
        case 'f':
        {
            String falseString = new String( pdfSource.readFully(5), ISO_8859_1 );
            if( falseString.equals( FALSE ) )
            {
                retval = COSBoolean.FALSE;
            }
            else
            {
                throw new IOException( "expected false actual='" + falseString + "' " + pdfSource + 
                        "' at offset " + pdfSource.getPosition());
            }
            break;
        }
        case 'R':
            pdfSource.read();
            retval = new COSObject(null);
            break;
        case (char)-1:
            return null;
        default:
        {
            if( Character.isDigit(c) || c == '-' || c == '+' || c == '.')
            {
                StringBuilder buf = new StringBuilder();
                int ic = pdfSource.read();
                c = (char)ic;
                while( Character.isDigit( c )||
                        c == '-' ||
                        c == '+' ||
                        c == '.' ||
                        c == 'E' ||
                        c == 'e' )
                {
                    buf.append( c );
                    ic = pdfSource.read();
                    c = (char)ic;
                }
                if( ic != -1 )
                {
                    pdfSource.rewind(1);
                }
                retval = COSNumber.get( buf.toString() );
            }
            else
            {
                //This is not suppose to happen, but we will allow for it
                //so we are more compatible with POS writers that don't
                //follow the spec
                String badString = readString();
                if( badString == null || badString.length() == 0 )
                {
                    int peek = pdfSource.peek();
                    // we can end up in an infinite loop otherwise
                    throw new IOException( "Unknown dir object c='" + c +
                            "' cInt=" + (int)c + " peek='" + (char)peek 
                            + "' peekInt=" + peek + " " + pdfSource.getPosition() );
                }

                // if it's an endstream/endobj, we want to put it back so the caller will see it
                if(ENDOBJ_STRING.equals(badString) || ENDSTREAM_STRING.equals(badString))
                {
                    pdfSource.rewind(badString.getBytes(ISO_8859_1).length);
                }
            }
        }
        }
        return retval;
    }

    /**
     * This will read the next string from the stream.
     *
     * @return The string that was read from the stream.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    protected String readString() throws IOException
    {
        skipSpaces();
        StringBuilder buffer = new StringBuilder();
        int c = pdfSource.read();
        while( !isEndOfName((char)c) && c != -1 )
        {
            buffer.append( (char)c );
            c = pdfSource.read();
        }
        if (c != -1)
        {
            pdfSource.rewind(1);
        }
        return buffer.toString();
    }
    
    /**
     * Read one String and throw an exception if it is not the expected value.
     *
     * @param expectedString the String value that is expected.
     * @throws IOException if the String char is not the expected value or if an
     * I/O error occurs.
     */
    protected void readExpectedString(String expectedString) throws IOException
    {
        readExpectedString(expectedString.toCharArray(), false);
    }

    /**
     * Reads given pattern from {@link #pdfSource}. Skipping whitespace at start and end if wanted.
     * 
     * @param expectedString pattern to be skipped
     * @param skipSpaces if set to true spaces before and after the string will be skipped
     * @throws IOException if pattern could not be read
     */
    protected final void readExpectedString(final char[] expectedString, boolean skipSpaces) throws IOException
    {
        if (skipSpaces) {
            skipSpaces();
        }
        for (char c : expectedString)
        {
            if (pdfSource.read() != c)
            {
                throw new IOException("Expected string '" + new String(expectedString)
                        + "' but missed at character '" + c + "' at offset "
                        + pdfSource.getPosition());
            }
        }
        if (skipSpaces) {
            skipSpaces();
        }
    }

    /**
     * Read one char and throw an exception if it is not the expected value.
     *
     * @param ec the char value that is expected.
     * @throws IOException if the read char is not the expected value or if an
     * I/O error occurs.
     */
    protected void readExpectedChar(char ec) throws IOException
    {
        char c = (char) pdfSource.read();
        if (c != ec)
        {
            throw new IOException("expected='" + ec + "' actual='" + c + "' at offset " + pdfSource.getPosition());
        }
    }
    
    /**
     * This will read the next string from the stream up to a certain length.
     *
     * @param length The length to stop reading at.
     *
     * @return The string that was read from the stream of length 0 to length.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    protected String readString( int length ) throws IOException
    {
        skipSpaces();

        int c = pdfSource.read();

        //average string size is around 2 and the normal string buffer size is
        //about 16 so lets save some space.
        StringBuilder buffer = new StringBuilder(length);
        while( !isWhitespace(c) && !isClosing(c) && c != -1 && buffer.length() < length &&
                c != '[' &&
                c != '<' &&
                c != '(' &&
                c != '/' )
        {
            buffer.append( (char)c );
            c = pdfSource.read();
        }
        if (c != -1)
        {
            pdfSource.rewind(1);
        }
        return buffer.toString();
    }

    /**
     * This will tell if the next character is a closing brace( close of PDF array ).
     *
     * @return true if the next byte is ']', false otherwise.
     *
     * @throws IOException If an IO error occurs.
     */
    protected boolean isClosing() throws IOException
    {
        return isClosing(pdfSource.peek());
    }

    /**
     * This will tell if the next character is a closing brace( close of PDF array ).
     *
     * @param c The character to check against end of line
     * @return true if the next byte is ']', false otherwise.
     */
    protected boolean isClosing(int c)
    {
        return c == ']';
    }

    /**
     * This will read bytes until the first end of line marker occurs.
     * NOTE: The EOL marker may consists of 1 (CR or LF) or 2 (CR and CL) bytes
     * which is an important detail if one wants to unread the line.
     *
     * @return The characters between the current position and the end of the line.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    protected String readLine() throws IOException
    {
        if (pdfSource.isEOF())
        {
            throw new IOException( "Error: End-of-File, expected line");
        }

        StringBuilder buffer = new StringBuilder( 11 );

        int c;
        while ((c = pdfSource.read()) != -1)
        {
            // CR and LF are valid EOLs
            if (isEOL(c))
            {
                break;
            }
            buffer.append( (char)c );
        }
        // CR+LF is also a valid EOL 
        if (isCR(c) && isLF(pdfSource.peek()))
        {
            pdfSource.read();
        }
        return buffer.toString();
    }

    /**
     * This will read bytes until the first end of line marker occurs, but EOL markers
     * will not skipped.
     *
     * @return The characters between the current position and the end of the line.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    protected String readLineWithoutWhitespacesSkip() throws IOException {
        if (pdfSource.isEOF())
        {
            throw new IOException( "Error: End-of-File, expected line");
        }

        StringBuilder buffer = new StringBuilder( 11 );

        int c;
        while ((c = pdfSource.read()) != -1)
        {
            // CR and LF are valid EOLs
            if (isEOL(c) || c == 32)
            {
                pdfSource.rewind(1);
                break;
            }
            buffer.append( (char)c );
        }
        return buffer.toString();
    }

    /**
     * This will tell if the next byte to be read is an end of line byte.
     *
     * @return true if the next byte is 0x0A or 0x0D.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    protected boolean isEOL() throws IOException
    {
        return isEOL(pdfSource.peek());
    }

    /**
     * This will tell if the next byte to be read is an end of line byte.
     *
     * @param c The character to check against end of line
     * @return true if the next byte is 0x0A or 0x0D.
     */
    protected boolean isEOL(int c)
    {
        return isLF(c) || isCR(c);
    }

    private boolean isLF(int c)
    {
        return ASCII_LF == c;
    }

    private boolean isCR(int c)
    {
        return ASCII_CR == c;
    }
    
    /**
     * This will tell if the next byte is whitespace or not.
     *
     * @return true if the next byte in the stream is a whitespace character.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    protected boolean isWhitespace() throws IOException
    {
        return isWhitespace( pdfSource.peek() );
    }

    /**
     * This will tell if a character is whitespace or not.  These values are
     * specified in table 1 (page 12) of ISO 32000-1:2008.
     * @param c The character to check against whitespace
     * @return true if the character is a whitespace character.
     */
    protected boolean isWhitespace( int c )
    {
        return c == 0 || c == 9 || c == 12  || c == ASCII_LF
        || c == ASCII_CR || c == ASCII_SPACE;
    }

    /**
     * This will tell if the next byte is a space or not.
     *
     * @return true if the next byte in the stream is a space character.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    protected boolean isSpace() throws IOException
    {
        return isSpace( pdfSource.peek() );
    }
    
    /**
     * This will tell if the given value is a space or not.
     * 
     * @param c The character to check against space
     * @return true if the next byte in the stream is a space character.
     */
    protected boolean isSpace(int c)
    {
        return ASCII_SPACE == c;
    }

    /**
     * This will tell if the next byte is a digit or not.
     *
     * @return true if the next byte in the stream is a digit.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    protected boolean isDigit() throws IOException
    {
        return isDigit( pdfSource.peek() );
    }

    /**
     * This will tell if the given value is a digit or not.
     * 
     * @param c The character to be checked
     * @return true if the next byte in the stream is a digit.
     */
    protected static boolean isDigit(int c)
    {
        return c >= ASCII_ZERO && c <= ASCII_NINE;
    }

    /**
     * This will skip all spaces and comments that are present.
     *
     * @return number of spaces skipped
     * @throws IOException If there is an error reading from the stream.
     */
    protected int skipSpaces() throws IOException
    {
        int c = pdfSource.read();
        int count = 1;
        // 37 is the % character, a comment
        while( isWhitespace(c) || c == 37)
        {
            if ( c == 37 )
            {
                // skip past the comment section
                c = pdfSource.read();
                while(!isEOL(c) && c != -1)
                {
                    c = pdfSource.read();
                    count++;
                }
            }
            else
            {
                c = pdfSource.read();
            }
            count++;
        }
        if (c != -1)
        {
            pdfSource.rewind(1);
            count--;
        }
        return count;
    }

    /**
     * This will read a long from the Stream and throw an {@link IOException} if
     * the long value is negative or has more than 10 digits (i.e. : bigger than
     * {@link #OBJECT_NUMBER_THRESHOLD})
     *
     * @return the object number being read.
     * @throws IOException if an I/O error occurs
     */
    protected long readObjectNumber() throws IOException
    {
        long retval = readLong();
        if (retval < 0 || retval >= OBJECT_NUMBER_THRESHOLD)
        {
            throw new IOException("Object Number '" + retval + "' has more than 10 digits or is negative");
        }
        return retval;
    }

    /**
     * This will read a integer from the Stream and throw an {@link IllegalArgumentException} if the integer value
     * has more than the maximum object revision (i.e. : bigger than {@link #GENERATION_NUMBER_THRESHOLD})
     * @return the generation number being read.
     * @throws IOException if an I/O error occurs
     */
    protected int readGenerationNumber() throws IOException
    {
        int retval = readInt();
        if(retval < 0 || retval > GENERATION_NUMBER_THRESHOLD)
        {
            throw new IOException("Generation Number '" + retval + "' has more than 5 digits");
        }
        return retval;
    }
    
    /**
     * This will read an integer from the stream.
     *
     * @return The integer that was read from the stream.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    protected int readInt() throws IOException
    {
        skipSpaces();
        int retval = 0;

        StringBuilder intBuffer = readStringNumber();

        try
        {
            retval = Integer.parseInt( intBuffer.toString() );
        }
        catch( NumberFormatException e )
        {
            pdfSource.rewind(intBuffer.toString().getBytes(ISO_8859_1).length);
            throw new IOException( "Error: Expected an integer type at offset "+pdfSource.getPosition(), e);
        }
        return retval;
    }
    

    /**
     * This will read an long from the stream.
     *
     * @return The long that was read from the stream.
     *
     * @throws IOException If there is an error reading from the stream.
     */
    protected long readLong() throws IOException
    {
        skipSpaces();
        long retval = 0;

        StringBuilder longBuffer = readStringNumber();

        try
        {
            retval = Long.parseLong(longBuffer.toString() );
        }
        catch( NumberFormatException e )
        {
            pdfSource.rewind(longBuffer.toString().getBytes(ISO_8859_1).length);
            throw new IOException( "Error: Expected a long type at offset "
                    + pdfSource.getPosition() + ", instead got '" + longBuffer + "'", e);
        }
        return retval;
    }

    /**
     * This method is used to read a token by the {@linkplain #readInt()} method
     * and the {@linkplain #readLong()} method.
     *
     * @return the token to parse as integer or long by the calling method.
     * @throws IOException throws by the {@link #pdfSource} methods.
     */
    protected final StringBuilder readStringNumber() throws IOException
    {
        int lastByte = 0;
        StringBuilder buffer = new StringBuilder();
        while( (lastByte = pdfSource.read() ) != ASCII_SPACE &&
                lastByte != ASCII_LF &&
                lastByte != ASCII_CR &&
                lastByte != 60 && //see sourceforge bug 1714707
                lastByte != '[' && // PDFBOX-1845
                lastByte != '(' && // PDFBOX-2579
                lastByte != 0 && //See sourceforge bug 853328
                lastByte != -1 )
        {
            buffer.append( (char)lastByte );
        }
        if( lastByte != -1 )
        {
            pdfSource.rewind(1);
        }
        return buffer;
    }

	/**
     * Calculates actual byte range for all ByteRangeValidation structures for
     * which it is not determined yet and fills set of signatures with good byte
     * range in COS document.
     */
    protected void processByteRangeValidationStructures() {
        try {
            for(ByteRangeValidationStructure structure: byteRangeValidationStructures) {
                if(!structure.isActualByteRangeCalculated) {
                    if(structure.indirectReference != null) {
                        processIndirectByteRangeValidationStructure(structure);
                    } else throw new IllegalStateException("Byte range is not calculated and indirect reference is not present.");
                }
                if(structure.isValidByteRange()) {
                    document.getSignaturesWithGoodByteRange().add(structure.dictionary);
                }
            }
        } catch (IOException ex) {
            LOG.error("Error in reading file stream", ex);
        }
    }

	/**
     * Calculates actual byte range for ByteRangeValidation structure for which
     * it is not determined yet. Method recursively skips all indirect references.
     * @param structure
     * @throws IOException
     */
    private void processIndirectByteRangeValidationStructure(ByteRangeValidationStructure structure)
            throws IOException {
        pdfSource.seek(document.getXrefTable().get(structure.indirectReference) +
                         document.getHeaderOffset());
        skipSpaces();
        long numOffset1 = pdfSource.getPosition();
        COSBase number = parseDirObject();
        long numOffset2 = pdfSource.getPosition();
        skipSpaces();
        if (!isDigit()) {
            structure.setContentsBeginningOffset(numOffset1);
            structure.setContentsEndingOffset(numOffset2);
            return;
        }
        long genOffset = pdfSource.getPosition();
        COSBase generationNumber = parseDirObject();
        skipSpaces();
        int c = pdfSource.read();
        if(c == 'R') {  // Indirect reference
            if (!(number instanceof COSInteger)) {
                throw new IOException("expected number, actual=" + number + " at offset " + numOffset1);
            }
            if (!(generationNumber instanceof COSInteger)) {
                throw new IOException("expected number, actual=" + number + " at offset " + genOffset);
            }
            COSObjectKey key = new COSObjectKey(((COSInteger) number).longValue(),
                    ((COSInteger) generationNumber).intValue());
            long keyOffset = this.document.getXrefTable().get(key);
            pdfSource.seek(keyOffset + document.getHeaderOffset());
            parseSignatureDictionaryValue(structure);    // Recursive parsing to get to the contents hex string itself
        } if(c == 'o') {    // Object itself
            readExpectedChar('b');
            readExpectedChar('j');
            skipSpaces();
            numOffset1 = pdfSource.getPosition();
            parseCOSString();
            numOffset2 = pdfSource.getPosition();
            structure.setContentsBeginningOffset(numOffset1);
            structure.setContentsEndingOffset(numOffset2);
        } else {
            throw new IOException("\"R\" or \"obj\" expected, but \'" + (char)c + "\' found.");
        }
    }

	/**
     * Scans stream till next %%EOF is found.
     * @param currentOffset byte offset of position, from which scanning strats
     * @return number of byte that contains 'F' in %%EOF
     * @throws IOException
     */
    protected long getOffsetOfNextEOF(long currentOffset) throws IOException {
        byte [] buffer = new byte[EOF_STRING.length];
        pdfSource.seek(currentOffset + document.getHeaderOffset());
        pdfSource.read(buffer);
        pdfSource.rewind(buffer.length - 1);
        while(!Arrays.equals(buffer,EOF_STRING)) {	//TODO: does it need to be optimized?
            pdfSource.read(buffer);
            if(pdfSource.isEOF()) {
                pdfSource.seek(currentOffset + document.getHeaderOffset());
                return pdfSource.length();
            }
            pdfSource.rewind(buffer.length - 1);
        }
        long result = pdfSource.getPosition() + buffer.length - 1;
        pdfSource.seek(currentOffset + document.getHeaderOffset());
        return result;
    }

	/**
	 * Structure that contains actual byte range of particular signature or
     * COS object key in case if /Contents is present indirectly.
     * COS dictionary is considered to be signature in this context if it
     * contains /Contents and /ByteRange entries. (Spec 32000-2008 says that
     * entry Type is optional in Signature dictionary)
     */
    protected class ByteRangeValidationStructure {
        private COSDictionary dictionary;
        private final long [] byteRangeOffsets = new long[3];
        private boolean isActualByteRangeCalculated = false;
        COSObjectKey indirectReference = null;

		/**
         * Constructor from dictionary.
         * @param dictionary
         */
        public ByteRangeValidationStructure(COSDictionary dictionary) {
            this.dictionary = dictionary;
            for(int i = 0; i < 3; ++i) {
                byteRangeOffsets[i] = -1;
            }
        }

		/**
		 * @return true if contained dictionary contains /Contents and
         * /ByteRange entries.
         */
        public boolean isSignature() {
            if(dictionary.containsKey(COSName.TYPE) &&
                    ((COSName)dictionary.getDictionaryObject(COSName.TYPE)).compareTo(COSName.SIG) != 0) {
                return false;
            }
            return (dictionary.containsKey(COSName.CONTENTS) &&
                    dictionary.containsKey(COSName.BYTERANGE));
        }

		/**
         * Sets the offset of beginning of signature /Contents hex string
         * respectively to the beginning of document itself.
         * @param offset is value of offset in bytes.
         */
        void setContentsBeginningOffset(long offset) {
            byteRangeOffsets[0] = offset - document.getHeaderOffset();
            checkCalculatedActualByteRange();
        }

		/**
		 * Sets the offset of ending of signature /Contents hex string
         * respectively to the beginning of document itself.
         * @param offset is value of offset in bytes.
         */
        void setContentsEndingOffset(long offset) {
            byteRangeOffsets[1] = offset - document.getHeaderOffset();
            checkCalculatedActualByteRange();
        }

		/**
		 * Sets the offset of %%EOF, corresponding to given dictionary
         * respectively to the beginning of document itself.
         * @param offset is value of offset in bytes.
         */
        void setFirstEofOffset(long offset) {
            byteRangeOffsets[2] = offset - document.getHeaderOffset();
            checkCalculatedActualByteRange();
        }

		/**
         * @param indirectReference indirect reference to value obtained by
         * /Contents key.
         */
        void setIndirectReference(COSObjectKey indirectReference) {
            this.indirectReference = indirectReference;
        }

        /**
         * @return true if entry /ByteRange in dictionary is equal to actual
         * byte range.
         */
        boolean isValidByteRange() {
            try {
                COSArray byteRange =
                        (COSArray) dictionary.getDictionaryObject(COSName.BYTERANGE);
                COSBase num = byteRange.get(0);
                if(((COSInteger) num).longValue() != 0) {
                    return false;
                }
                for(int i = 1; i < 2; ++i) {
                    num = byteRange.get(i);
                    if(((COSInteger) num).longValue() != byteRangeOffsets[i - 1]) {
                        return false;
                    }
                }
                num = byteRange.get(3);
                if(((COSInteger) num).longValue() != byteRangeOffsets[2] -
                        byteRangeOffsets[1] + 1) {
                    return false;
                }
                return true;
            } catch (ClassCastException e) {
                LOG.warn("ByteRange array contains not COSIntegers", e);
                return false;
            }
        }

        private void checkCalculatedActualByteRange() {
            for(int i = 0; i < 3; ++i) {
                if(byteRangeOffsets[i] == -1) {
                    isActualByteRangeCalculated = false;
                    return;
                }
            }
            isActualByteRangeCalculated = true;
        }
    }
}
