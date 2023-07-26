package com.mysite.core.servlets;

import com.adobe.aemfd.docmanager.Document;
import com.adobe.fd.assembler.client.AssemblerOptionSpec;
import com.adobe.fd.assembler.client.AssemblerResult;
import com.adobe.fd.assembler.client.OperationException;
import com.adobe.fd.assembler.service.AssemblerService;
import com.adobe.fd.output.api.OutputService;
import com.adobe.fd.output.api.OutputServiceException;
import com.adobe.fd.output.api.PDFOutputOptions;
import com.adobe.granite.asset.api.Asset;
import com.adobe.granite.asset.api.AssetManager;
import com.adobe.granite.asset.api.Rendition;
import org.apache.commons.io.IOUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

@Component(service = Servlet.class, property = {
        Constants.SERVICE_DESCRIPTION + "= Converts agreement data into a PDF for signing",
        "sling.servlet.methods=" + HttpConstants.METHOD_POST,
        "sling.servlet.paths=" + "/bin/iec/mergeDocument",})
/**
 * Create a merged drawing document based on:
 * * XML data provided in POST request
 * * Document template XDP
 * * Drawing document
 */
public class DrawingBuilderServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(DrawingBuilderServlet.class);

    @Reference
    private transient OutputService outputService;
    @Reference
    private transient AssemblerService assemblerService;

    private final String CHARSET = "windows-1255";

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        try {
            final ResourceResolver resourceResolver = request.getResourceResolver();
            final AssetManager assetManager = resourceResolver.adaptTo(AssetManager.class);

            // get the data from the posted request
            final Document data = getRequestInputAsDocument(request, CHARSET);

            final String templatePath = Optional.of(request.getParameter("template"))
                    .orElse("/content/dam/iec/templates/PDF_CVL_MECH_BACKGROUNDS_A0AUTOCAD_A0.pdf");
            final String drawingPath = Optional.of(request.getParameter("drawing"))
                    .orElse("/content/dam/iec/fragments/TR0_20-B00-1GH499-00before.pdf");
            final String ddxPath = Optional.of(request.getParameter("ddx"))
                    .orElse("/content/dam/iec/ddx.xml");

            // get the template to fill with data
            final Document template = getAssetAsDocument(assetManager, templatePath);
            // get the drawing to overlay
            final Document drawing =  getAssetAsDocument(assetManager, drawingPath);
            // get the ddx that describes the assembled document
            final Document ddx = getAssetAsDocument(assetManager, ddxPath);
            log.debug("ddx: {}", ddx);

            // merge the data with the template
            final Document mergedTemplate = merge(data, template);
            // overlay the drawing on the filled template
            final Document output = overlay(mergedTemplate, ddx, drawing);
            output.setContentType("application/xml;charset=" + CHARSET);

            // copy the document to the http response output
            try (final InputStream ois = output.getInputStream()) {
                response.setContentType("application/pdf");
                IOUtils.copy(ois, response.getOutputStream());
            }
        } catch (Exception e) {
            log.error("Exception", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Exception generating pdf");
        }
    }

    /**
     * Merge the data with the document template
     * @param data XML input data as a Document
     * @param template template XDP document with which to merge the data
     * @return a Document containing the template PDF merged with the data
     * @throws OutputServiceException
     */
    public Document merge(final Document data, final Document template) throws OutputServiceException {
        return this.outputService.generatePDFOutput(template, data, new PDFOutputOptions());
    }

    /**
     * Merge a template (with data) and a drawing using the AssemblyService DDX description
     * @param mergedTemplate document template merged with data
     * @param ddx description of assembled document
     * @param drawing drawing to overlay
     * @return final document containing template, data and drawing
     * @throws OperationException
     */
    public Document overlay(final Document mergedTemplate, final Document ddx, final Document drawing) throws OperationException {
        final Map<String, Object> inputs = Map.of(
                "template.pdf", mergedTemplate,
                "drawing.pdf", drawing);
        final AssemblerResult assemblerResult = this.assemblerService.invoke(ddx, inputs, new AssemblerOptionSpec());
        final Map<String, Document> outputDocs = assemblerResult.getDocuments();

        return outputDocs.get("result.pdf");
    }

    /**
     * Load an asset from the DAM as a Document.
     * This might be an (XDP) PDF, drawing PDF or XML file.
     * @param assetMgr service used to load asset
     * @param path path of the asset (e.g. /content/dam/...)
     * @return Asset as a document
     */
    private Document getAssetAsDocument(final AssetManager assetMgr, final String path) {
        final Asset asset = assetMgr.getAsset(path);
        final Rendition rendition = asset.getRendition("original");
        return new Document(rendition.getStream());
    }

    /**
     * Load the XML from the POST request payload as a Document
     * @param request http request
     * @param charset charset of the content
     * @return Document representing the XML
     * @throws IOException
     */
    private Document getRequestInputAsDocument(SlingHttpServletRequest request, String charset) throws IOException {
         final String xml = IOUtils.toString(request.getInputStream(), charset);
         log.debug("xml: {}", xml);
         return new Document(xml.getBytes(charset));
    }
}
