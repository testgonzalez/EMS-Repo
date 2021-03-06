/*******************************************************************************
 * Copyright (c) <2013>, California Institute of Technology ("Caltech").
 * U.S. Government sponsorship acknowledged.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice, this list of
 *    conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice, this list
 *    of conditions and the following disclaimer in the documentation and/or other materials
 *    provided with the distribution.
 *  - Neither the name of Caltech nor its operating division, the Jet Propulsion Laboratory,
 *    nor the names of its contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY
 * AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/

package gov.nasa.jpl.view_repo.webscripts;

import gov.nasa.jpl.mbee.util.TimeUtils;
import gov.nasa.jpl.mbee.util.Utils;
import gov.nasa.jpl.view_repo.util.Acm;
import gov.nasa.jpl.view_repo.util.EmsScriptNode;
import gov.nasa.jpl.view_repo.util.NodeUtil;
import gov.nasa.jpl.view_repo.util.WorkspaceNode;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.*;
import org.alfresco.repo.model.Repository;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

/**
 * Model search service that returns a JSONArray of elements
 * @author cinyoung
 *
 */
public class ModelSearch extends ModelGet {
    static Logger logger = Logger.getLogger(ModelSearch.class);

    public ModelSearch() {
        super();
    }


    public ModelSearch(Repository repositoryHelper, ServiceRegistry registry) {
        super(repositoryHelper, registry);
    }

    
    protected final Map<String, String> searchTypesMap = new HashMap<String, String>() {
        private static final long serialVersionUID = -7336887332666278453L;
        {
            put("documentation", "@sysml\\:documentation:\"");
            put("name", "@sysml\\:name:\"");
            put("id", "@sysml\\:id:\"");
            put("aspect", "ASPECT:\"{http://jpl.nasa.gov/model/sysml-lite/1.0}"); 
            put("appliedMetatypes", "@sysml\\:appliedMetatypes:\"");
            put("metatypes", "@sysml\\:metatypes:\"");
        }
    };
    

    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {
        ModelSearch instance = new ModelSearch(repository, getServices());
        return instance.executeImplImpl(req,  status, cache, runWithoutTransactions);
    }

    @Override
    protected Map<String, Object> executeImplImpl(WebScriptRequest req, Status status, Cache cache) {
        printHeader( req );

        clearCaches();

        Map<String, Object> model = new HashMap<String, Object>();

        try {
            JSONObject top = NodeUtil.newJsonObject();
            JSONArray elementsJson = executeSearchRequest(req, top);

            top.put("elements", elementsJson);
            if (!Utils.isNullOrEmpty(response.toString())) top.put("message", response.toString());
            model.put("res", NodeUtil.jsonToString( top, 4 ));
        } catch (JSONException e) {
            log(Level.ERROR, HttpServletResponse.SC_BAD_REQUEST, "Could not create the JSON response");
            model.put("res", createResponseJson());
            e.printStackTrace();
        }

        status.setCode(responseStatus.getCode());

        printFooter();

        return model;
    }

    private JSONArray executeSearchRequest(WebScriptRequest req, JSONObject top ) throws JSONException {
        String keyword = req.getParameter("keyword");
        String propertyName = req.getParameter("propertyName");
        String[] filters = req.getParameter("filters") == null ? new String[]{"documentation"} : req.getParameter( "filters" ).split( "," );
        boolean evaluate = getBooleanArg( req, "evaluate", false );

        if (!Utils.isNullOrEmpty( keyword )) {

            // get timestamp if specified
            String timestamp = req.getParameter("timestamp");
            Date dateTime = TimeUtils.dateFromTimestamp( timestamp );

            Map<String, EmsScriptNode> rawResults = new HashMap<String, EmsScriptNode>();

            WorkspaceNode workspace = getWorkspace( req );

            for (String searchType: filters) {
                if ( !searchType.equals( "value" ) ) {
                    String lucenePrefix = searchTypesMap.get(searchType);
                    if (lucenePrefix != null) {
                        rawResults.putAll( searchForElements( searchTypesMap.get(searchType), keyword, false,
                                                              workspace, dateTime ) );
                    } else {
                        log(Level.INFO, HttpServletResponse.SC_BAD_REQUEST, "Unexpected filter type: " + searchType);
                        return null;
                    }
                } else {
                    
                    try {
                        Integer.parseInt(keyword);
                        rawResults.putAll( searchForElements( "@sysml\\:integer:\"", keyword, false,
                                                              workspace, dateTime) );
                        rawResults.putAll( searchForElements( "@sysml\\:naturalValue:\"", keyword, false,
                                                              workspace, dateTime) );
                    } catch (NumberFormatException nfe) {
                        // do nothing
                    }
                    
                    try {
                        // Need to do Double.toString() in case they left out the decimal in something like 5.0
                        double d = Double.parseDouble(keyword); 
                        rawResults.putAll( searchForElements( "@sysml\\:double:\"", Double.toString( d ), false,
                                                              workspace, dateTime) );
                    } catch (NumberFormatException nfe) {
                        // do nothing
                    }

                    if (keyword.equalsIgnoreCase( "true" ) || keyword.equalsIgnoreCase( "false" )) {
                        rawResults.putAll( searchForElements( "@sysml\\:boolean:\"", keyword, false,
                                                              workspace, dateTime) );
                    }

                    rawResults.putAll( searchForElements( "@sysml\\:string:\"", keyword, false,
                                                          workspace, dateTime) );
                }
            }

            // filter out _pkgs:
            for (String sysmlid: rawResults.keySet()) {
                if (!sysmlid.endsWith( "_pkg" )) {
                    elementsFound.put(sysmlid, rawResults.get( sysmlid ));
                }
            }
            
            filterValueSpecs(propertyName, workspace, dateTime);
            
            addElementProperties(workspace, dateTime);
            
            
            boolean checkReadPermission = true; //TODO -- REVIEW -- Should this be false?
            //handleElements(workspace, dateTime, true, evaluate);
            handleElements( workspace, dateTime, true, evaluate,
                            top, checkReadPermission  );
        }

        return elements;
    }
}
