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
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.*;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;

public class PDFParser extends COSParser
{
    private static final Log LOG = LogFactory.getLog(PDFParser.class);

    private String password = "";
    private InputStream keyStoreInputStream = null;
    private String keyAlias = null;

    private AccessPermission accessPermission;

    /**
     * Constructor.
     * 
     * @param source source representing the pdf.
     * @throws IOException If something went wrong.
     */
    public PDFParser(RandomAccessRead source) throws IOException
    {
        this(source, "", false);
    }

    /**
     * Constructor.
     * 
     * @param source input representing the pdf.
     * @param useScratchFiles use a file based buffer for temporary storage.
     * 
     * @throws IOException If something went wrong.
     */
    public PDFParser(RandomAccessRead source, boolean useScratchFiles) throws IOException
    {
        this(source, "", useScratchFiles);
    }

    /**
     * Constructor.
     * 
     * @param source input representing the pdf.
     * @param decryptionPassword password to be used for decryption.
     * @throws IOException If something went wrong.
     */
    public PDFParser(RandomAccessRead source, String decryptionPassword) throws IOException
    {
        this(source, decryptionPassword, false);
    }

    /**
     * Constructor.
     * 
     * @param source input representing the pdf.
     * @param decryptionPassword password to be used for decryption.
     * @param useScratchFiles use a file based buffer for temporary storage.
     *
     * @throws IOException If something went wrong.
     */
    public PDFParser(RandomAccessRead source, String decryptionPassword, boolean useScratchFiles)
            throws IOException
    {
        this(source, decryptionPassword, null, null, useScratchFiles);
    }

    /**
     * Constructor.
     * 
     * @param source input representing the pdf.
     * @param decryptionPassword password to be used for decryption.
     * @param keyStore key store to be used for decryption when using public key security 
     * @param alias alias to be used for decryption when using public key security
     *
     * @throws IOException If something went wrong.
     */
    public PDFParser(RandomAccessRead source, String decryptionPassword, InputStream keyStore,
            String alias) throws IOException
    {
        this(source, decryptionPassword, keyStore, alias, false);
    }

    /**
     * Constructor.
     * 
     * @param source input representing the pdf.
     * @param decryptionPassword password to be used for decryption.
     * @param keyStore key store to be used for decryption when using public key security 
     * @param alias alias to be used for decryption when using public key security
     * @param useScratchFiles use a buffer for temporary storage.
     *
     * @throws IOException If something went wrong.
     */
    public PDFParser(RandomAccessRead source, String decryptionPassword, InputStream keyStore,
            String alias, boolean useScratchFiles) throws IOException
    {
        pdfSource = source;
        fileLen = source.length();
        password = decryptionPassword;
        keyStoreInputStream = keyStore;
        keyAlias = alias;
        init(useScratchFiles);
    }

	/**
	 * Constructor.
	 *
	 * @param source input representing the pdf.
	 * @param decryptionPassword password to be used for decryption.
	 * @param keyStore key store to be used for decryption when using public key security
	 * @param alias alias to be used for decryption when using public key security
	 * @param useScratchFiles use a buffer for temporary storage.
	 * @param validationParsing true if need to use validation parser
	 *
	 * @throws IOException If something went wrong.
	 */
	public PDFParser(RandomAccessRead source, String decryptionPassword, InputStream keyStore,
					 String alias, boolean useScratchFiles, boolean validationParsing) throws IOException
	{
		this(source, decryptionPassword, keyStore, alias, useScratchFiles);
		this.validationParsing = validationParsing;
	}

    private void init(boolean useScratchFiles) throws IOException
    {
        String eofLookupRangeStr = System.getProperty(SYSPROP_EOFLOOKUPRANGE);
        if (eofLookupRangeStr != null)
        {
            try
            {
                setEOFLookupRange(Integer.parseInt(eofLookupRangeStr));
            }
            catch (NumberFormatException nfe)
            {
                LOG.debug("System property " + SYSPROP_EOFLOOKUPRANGE
                        + " does not contain an integer value, but: '" + eofLookupRangeStr + "'");
            }
        }
        document = new COSDocument(useScratchFiles);
    }

    /**
     * This will get the PD document that was parsed.  When you are done with
     * this document you must call close() on it to release resources.
     *
     * @return The document at the PD layer.
     *
     * @throws IOException If there is an error getting the document.
     */
    public PDDocument getPDDocument() throws IOException
    {
        // this is required to support
        if (validationParsing) {
            getDocument().setLastTrailer(getLastTrailer());
            getDocument().setFirstPageTrailer(getFirstTrailer());
        }
        return new PDDocument( getDocument(), pdfSource, accessPermission );
    }

    /**
     * The initial parse will first parse only the trailer, the xrefstart and all xref tables to have a pointer (offset)
     * to all the pdf's objects. It can handle linearized pdfs, which will have an xref at the end pointing to an xref
     * at the beginning of the file. Last the root object is parsed.
     * 
     * @throws IOException If something went wrong.
     */
    protected void initialParse() throws IOException
    {
        COSDictionary trailer = null;
        // parse startxref
        long startXRefOffset = getStartxrefOffset();
        if (startXRefOffset > -1)
        {
            trailer = parseXref(startXRefOffset);
        }
        else if (validationParsing)
		{
            throw new IOException("Document doesn't contain startxref keyword");
        }
		else if (isLenient())
		{
            trailer = rebuildTrailer();
        }
        // prepare decryption if necessary
        prepareDecryption();
    
        parseTrailerValuesDynamically(trailer);

		//VERAPDF_PT-129: current way of document parsing exclude Info dictionary 'deep' parsing
		if (validationParsing)
		{
			if (trailer != null)
			{
				if (validationParsing)
				{
					parseSuspensionObjects();
				} else
				{
					parseDictObjects(trailer, (COSName[]) null);
				}
                document.setDecrypted();
            }
            isLinearized(fileLen);
        } else
		{
            COSObject catalogObj = document.getCatalog();
            if (catalogObj != null && catalogObj.getObject() instanceof COSDictionary)
			{
                parseDictObjects((COSDictionary) catalogObj.getObject(), (COSName[]) null);
                document.setDecrypted();
            }
        }

        initialParseDone = true;
    }

    /**
     * This will parse the stream and populate the PDDocument object. This will close the keystore stream when it is
     * done parsing. Lenient mode is active by default.
     *
     * @throws InvalidPasswordException If the password is incorrect.
     * @throws IOException If there is an error reading from the stream or corrupt data is found.
     */
    public PDDocument parse() throws IOException
    {
        return parse(true);
    }

    /**
     * This will parse the stream and populate the PDDocument object. This will close the keystore stream when it is
     * done parsing.
     *
     * @param lenient activate leniency if set to true
     * @throws InvalidPasswordException If the password is incorrect.
     * @throws IOException If there is an error reading from the stream or corrupt data is found.
     */
    public PDDocument parse(boolean lenient) throws IOException
    {
        setLenient(lenient);
        // set to false if all is processed
        boolean exceptionOccurred = true;
        try
        {
            // PDFBOX-1922 read the version header and rewind
            if (!parsePDFHeader() && !parseFDFHeader())
            {
                throw new IOException( "Error: Header doesn't contain versioninfo" );
            }

            if (!initialParseDone)
            {
                initialParse();
            }
            exceptionOccurred = false;
            PDDocument pdDocument = createDocument();
            pdDocument.setEncryptionDictionary(getEncryption());
            return pdDocument;
        }
        finally
        {
            if (exceptionOccurred && document != null)
            {
                IOUtils.closeQuietly(document);
                document = null;
            }
        }
    }

    /**
     * Create the resulting document. Maybe overwritten if the parser uses another class as document.
     *
     * @return the resulting document
     * @throws IOException if the method is called before parsing the document
     */
    protected PDDocument createDocument() throws IOException
    {
        return new PDDocument(document, pdfSource, getAccessPermission());
    }


    /**
     * Prepare for decryption.
     * 
     * @throws IOException if something went wrong
     */
    private void prepareDecryption() throws IOException
    {
        COSBase trailerEncryptItem = document.getTrailer().getItem(COSName.ENCRYPT);
        if (trailerEncryptItem != null && !(trailerEncryptItem instanceof COSNull))
        {
            if (trailerEncryptItem instanceof COSObject)
            {
                COSObject trailerEncryptObj = (COSObject) trailerEncryptItem;
                parseDictionaryRecursive(trailerEncryptObj);
            }
            try
            {
                PDEncryption encryption = new PDEncryption(document.getEncryptionDictionary());
    
                DecryptionMaterial decryptionMaterial;
                if (keyStoreInputStream != null)
                {
                    KeyStore ks = KeyStore.getInstance("PKCS12");
                    ks.load(keyStoreInputStream, password.toCharArray());
    
                    decryptionMaterial = new PublicKeyDecryptionMaterial(ks, keyAlias, password);
                }
                else
                {
                    decryptionMaterial = new StandardDecryptionMaterial(password);
                }
    
                securityHandler = encryption.getSecurityHandler();
                securityHandler.prepareForDecryption(encryption, document.getDocumentID(),
                        decryptionMaterial);
                accessPermission = securityHandler.getCurrentAccessPermission();
            }
            catch (IOException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                throw new IOException("Error (" + e.getClass().getSimpleName()
                        + ") while creating security handler for decryption", e);
            }
        }
    }

    /**
     * Resolves all not already parsed objects of a dictionary recursively.
     * 
     * @param dictionaryObject dictionary to be parsed
     * @throws IOException if something went wrong
     * 
     */
    private void parseDictionaryRecursive(COSObject dictionaryObject) throws IOException
    {
        parseObjectDynamically(dictionaryObject, true);
        COSDictionary dictionary = (COSDictionary)dictionaryObject.getObject();
        for(COSBase value : dictionary.getValues())
        {
            if (value instanceof COSObject)
            {
                COSObject object = (COSObject)value;
                if (object.getObject() == null)
                {
                    parseDictionaryRecursive(object);
                }
            }
        }
    }

}
