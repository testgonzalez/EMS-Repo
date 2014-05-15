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

import gov.nasa.jpl.view_repo.sysml.View;
import gov.nasa.jpl.view_repo.util.Acm.JSON_TYPE_FILTER;
import gov.nasa.jpl.view_repo.util.EmsScriptNode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.alfresco.repo.model.Repository;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.security.PermissionService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

// TODO -- this should be a subclass of ViewGet
public class ProductGet extends AbstractJavaWebScript {
	protected boolean gettingDisplayedElements = false;
    protected boolean gettingContainedViews = false;

    // injected via spring configuration
    protected boolean isViewRequest = false;


    public ProductGet() {
	    super();
	}
    
    public ProductGet(Repository repositoryHelper, ServiceRegistry registry) {
        super(repositoryHelper, registry);
    }
	
	@Override
	protected boolean validateRequest(WebScriptRequest req, Status status) {
		String productId = getProductId(req);
		if (!checkRequestVariable(productId, "id")) {
			return false;
		}
		
		EmsScriptNode product = findScriptNodeById(productId);
		if (product == null) {
			log(LogLevel.ERROR, "Product not found with id: " + productId + ".\n", HttpServletResponse.SC_NOT_FOUND);
			return false;
		}
		
		if (!checkPermissions(product, PermissionService.READ)) {
			return false;
		}
		
		return true;
	}
	
	protected static String getProductId( WebScriptRequest req ) {
        String productId = req.getServiceMatch().getTemplateVars().get("id");
        if ( productId == null ) {
            productId = req.getServiceMatch().getTemplateVars().get("modelid");
        }
        if ( productId == null ) {
            productId = req.getServiceMatch().getTemplateVars().get("elementid");
        }
        System.out.println("Got id = " + productId);
        boolean gotElementSuffix  = ( productId.toLowerCase().trim().endsWith("/elements") );
        if ( gotElementSuffix ) {
            productId = productId.substring( 0, productId.lastIndexOf( "/elements" ) );
        } else {
            boolean gotViewSuffix  = ( productId.toLowerCase().trim().endsWith("/views") );
            if ( gotViewSuffix ) {
                productId = productId.substring( 0, productId.lastIndexOf( "/views" ) );
            }
        }
        System.out.println("productId = " + productId);
        return productId;
	}

	protected static boolean isDisplayedElementRequest( WebScriptRequest req ) {
	    if ( req == null ) return false;
        String url = req.getURL();
        if ( url == null ) return false;
        boolean gotSuffix = ( url.toLowerCase().trim().endsWith("/elements") );
        return gotSuffix;
    }

	protected static boolean isContainedViewRequest( WebScriptRequest req ) {
	    if ( req == null ) return false;
        String url = req.getURL();
        if ( url == null ) return false;
        boolean gotSuffix = ( url.toLowerCase().trim().endsWith("/views") );
        return gotSuffix;
    }
	
	@Override
	protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {
        printHeader( req );

		clearCaches();
		
		Map<String, Object> model = new HashMap<String, Object>();

		JSONArray productsJson = null;
		if (validateRequest(req, status)) {
			String productId = getProductId( req );
			gettingDisplayedElements = isDisplayedElementRequest( req );
			if ( !gettingDisplayedElements ) {
			    gettingContainedViews  = isContainedViewRequest( req );
			} 
			System.out.println("productId = " + productId);
			
			// default recurse=true but recurse only applies to displayed elements and contained views
            boolean recurse = checkArgEquals(req, "recurse", "false") ? false : true;
			productsJson = handleProduct(productId, recurse);
		}

		if (responseStatus.getCode() == HttpServletResponse.SC_OK && productsJson != null) {
			try {
			    JSONObject json = new JSONObject();
                json.put( gettingDisplayedElements ? "elements"
                                                  : ( gettingContainedViews
                                                      ? "views" : "products" ),
                         productsJson );
				model.put("res", json.toString(4));
			} catch (JSONException e) {
				log(LogLevel.ERROR, "JSON creation error", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				model.put("res", response.toString());
                e.printStackTrace();
			}
		} else {
			model.put("res", response.toString());
		}

		status.setCode(responseStatus.getCode());

		printFooter();

		return model;
	}

	
	private JSONArray handleProduct(String productId, boolean recurse) {
	    JSONArray productsJson = new JSONArray();
		EmsScriptNode product = findScriptNodeById(productId);
		
		if (product == null) {
			log( LogLevel.ERROR, "Product not found with ID: " + productId,
			     HttpServletResponse.SC_NOT_FOUND );
		}

		if (checkPermissions(product, PermissionService.READ)){ 
            try {
                View v = new View( product );
                if ( gettingDisplayedElements ) {
                    System.out.println("+ + + + + gettingDisplayedElements");
                    Collection< EmsScriptNode > elems = v.getDisplayedElements();
                    for ( EmsScriptNode n : elems ) {
                        productsJson.put( n.toJSONObject( JSON_TYPE_FILTER.ELEMENT ) );
                    }
                } else if ( gettingContainedViews ) {
                    System.out.println("+ + + + + gettingContainedViews");
                    Collection< EmsScriptNode > elems = v.getContainedViews( recurse, null );
//                    LinkedHashSet< EmsScriptNode > elems = new LinkedHashSet<EmsScriptNode>();
//                    elems.addAll(v.getViewToViewPropertyViews());
//                    elems.addAll(v.getChildViewElements());
                    for ( EmsScriptNode n : elems ) {
                        productsJson.put( n.toJSONObject( JSON_TYPE_FILTER.VIEW ) );
                    }
                } else {
                    System.out.println("+ + + + + just the product");
                    productsJson.put( product.toJSONObject( JSON_TYPE_FILTER.PRODUCT ) );
                }
            } catch ( JSONException e ) {
                log( LogLevel.ERROR, "Could not create products JSON array",
                     HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
                e.printStackTrace();
            }
		}
		
		return productsJson;
	}

    /**
     * Need to differentiate between View or Element request - specified during Spring configuration
     * @param flag
     */
    public void setIsViewRequest(boolean flag) {
        isViewRequest = flag;
    }

}
