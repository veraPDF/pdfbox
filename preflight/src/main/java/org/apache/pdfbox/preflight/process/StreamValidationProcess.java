/*****************************************************************************
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 ****************************************************************************/

package org.apache.pdfbox.preflight.process;

import static org.apache.pdfbox.preflight.PreflightConstants.ERROR_SYNTAX_STREAM_DAMAGED;
import static org.apache.pdfbox.preflight.PreflightConstants.ERROR_SYNTAX_STREAM_FX_KEYS;
import static org.apache.pdfbox.preflight.PreflightConstants.ERROR_SYNTAX_STREAM_INVALID_FILTER;
import static org.apache.pdfbox.preflight.PreflightConstants.ERROR_SYNTAX_STREAM_LENGTH_INVALID;
import static org.apache.pdfbox.preflight.PreflightConstants.ERROR_SYNTAX_STREAM_LENGTH_MISSING;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.commons.io.IOUtils;
import static org.apache.commons.io.IOUtils.closeQuietly;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.preflight.PreflightContext;
import org.apache.pdfbox.preflight.ValidationResult.ValidationError;
import org.apache.pdfbox.preflight.exception.ValidationException;
import org.apache.pdfbox.preflight.utils.COSUtils;
import org.apache.pdfbox.preflight.utils.FilterHelper;
import org.apache.pdfbox.util.Charsets;

public class StreamValidationProcess extends AbstractProcess
{

    @Override
    public void validate(PreflightContext ctx) throws ValidationException
    {
        PDDocument pdfDoc = ctx.getDocument();
        COSDocument cDoc = pdfDoc.getDocument();

        List<?> lCOSObj = cDoc.getObjects();
        for (Object o : lCOSObj)
        {
            COSObject cObj = (COSObject) o;
            /*
             * If this object represents a Stream, the Dictionary must contain the Length key
             */
            COSBase cBase = cObj.getObject();
            if (cBase instanceof COSStream)
            {
                validateStreamObject(ctx, cObj);
            }
        }
    }

    public void validateStreamObject(PreflightContext context, COSObject cObj) throws ValidationException
    {
        COSStream streamObj = (COSStream) cObj.getObject();

        // ---- Check dictionary entries
        // ---- Only the Length entry is mandatory
        // ---- In a PDF/A file, F, FFilter and FDecodeParms are forbidden
        checkDictionaryEntries(context, streamObj);
        // ---- Check the Filter value(s)
        checkFilters(streamObj, context);
    }

    /**
     * This method checks if one of declared Filter is LZWdecode. If LZW is found, the result list is updated with an
     * error code.
     * 
     * @param stream the stream to check.
     * @param context the preflight context.
     */
    protected void checkFilters(COSStream stream, PreflightContext context)
    {
        COSBase bFilter = stream.getDictionaryObject(COSName.FILTER);
        if (bFilter != null)
        {
            COSDocument cosDocument = context.getDocument().getDocument();
            if (COSUtils.isArray(bFilter, cosDocument))
            {
                COSArray afName = (COSArray) bFilter;
                for (int i = 0; i < afName.size(); ++i)
                {
                    FilterHelper.isAuthorizedFilter(context, afName.getString(i));
                }
            }
            else if (bFilter instanceof COSName)
            {
                String fName = ((COSName) bFilter).getName();
                FilterHelper.isAuthorizedFilter(context, fName);
            }
            else
            {
                // ---- The filter type is invalid
                addValidationError(context, new ValidationError(ERROR_SYNTAX_STREAM_INVALID_FILTER,
                        "Filter should be a Name or an Array"));
            }
        }
        // else Filter entry is optional
    }

    private boolean readUntilStream(InputStream ra) throws IOException
    {
        boolean search = true;
        boolean maybe = false;
        int lastChar = -1;
        do
        {
            int c = ra.read();
            switch (c)
            {
            case 's':
                maybe = true;
                lastChar = c;
                break;
            case 't':
                if (maybe && lastChar == 's')
                {
                    lastChar = c;
                }
                else
                {
                    maybe = false;
                    lastChar = -1;
                }
                break;
            case 'r':
                if (maybe && lastChar == 't')
                {
                    lastChar = c;
                }
                else
                {
                    maybe = false;
                    lastChar = -1;
                }
                break;
            case 'e':
                if (maybe && lastChar == 'r')
                {
                    lastChar = c;
                }
                else
                {
                    maybe = false;
                }
                break;
            case 'a':
                if (maybe && lastChar == 'e')
                {
                    lastChar = c;
                }
                else
                {
                    maybe = false;
                }
                break;
            case 'm':
                if (maybe && lastChar == 'a')
                {
                    return true;
                }
                else
                {
                    maybe = false;
                }
                break;
            case -1:
                search = false;
                break;
            default:
                maybe = false;
                break;
            }
        } while (search);
        return false;
    }

    /**
     * Check dictionary entries. Only the Length entry is mandatory. In a PDF/A file, F, FFilter and FDecodeParms are
     * forbidden
     * 
     * @param context the preflight context.
     * @param streamObj the stream to check.
     */
    protected void checkDictionaryEntries(PreflightContext context, COSStream streamObj)
    {
        boolean len = streamObj.containsKey(COSName.LENGTH);
        boolean f = streamObj.containsKey(COSName.F);
        boolean ffilter = streamObj.containsKey(COSName.F_FILTER);
        boolean fdecParams = streamObj.containsKey(COSName.F_DECODE_PARMS);

        if (!len)
        {
            addValidationError(context, new ValidationError(ERROR_SYNTAX_STREAM_LENGTH_MISSING,
                    "Stream length is missing"));
        }

        if (f || ffilter || fdecParams)
        {
            addValidationError(context, new ValidationError(ERROR_SYNTAX_STREAM_FX_KEYS,
                    "F, FFilter or FDecodeParms keys are present in the stream dictionary"));
        }
    }
    
    private void addStreamLengthValidationError(PreflightContext context, COSObject cObj, int length, String endStream)
    {
        addValidationError(context, new ValidationError(ERROR_SYNTAX_STREAM_LENGTH_INVALID,
                "Stream length is invalid [cObj=" + cObj + "; defined length=" + length + "; buffer2=" + endStream + "]"));
    }

}
