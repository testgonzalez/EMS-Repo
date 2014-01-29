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

package gov.nasa.jpl.view_repo.util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.jscript.ScriptNode;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.AssociationRef;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.Path;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.repository.Path.ChildAssocElement;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.ResultSetRow;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.namespace.RegexQNamePattern;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.Status;

/**
 * Extension of ScriptNode to support EMS needs
 * @author cinyoung
 *
 */
public class EmsScriptNode extends ScriptNode {
	private static final long serialVersionUID = 9132455162871185541L;

	// provide logging capability of what is done
	private StringBuffer response = null;
	
	// provide status as necessary
	private Status status = null;
	
	boolean useFoundationalApi = true;

	// for lucene search
	protected static final StoreRef SEARCH_STORE = new StoreRef(StoreRef.PROTOCOL_WORKSPACE, "SpacesStore");

	public EmsScriptNode(NodeRef nodeRef, ServiceRegistry services) {
		super(nodeRef, services);
	}


	public EmsScriptNode(NodeRef nodeRef, ServiceRegistry services, StringBuffer response, Status status) {
		this(nodeRef, services);
		setStatus(status);
	}

	public EmsScriptNode(NodeRef nodeRef, ServiceRegistry services, StringBuffer response) {
		this(nodeRef, services);
		setResponse(response);
	}

	
	@Override
	public EmsScriptNode childByNamePath(String path) {
		ScriptNode child = super.childByNamePath(path);
		if (child == null) {
			return null;
		}
		return new EmsScriptNode(child.getNodeRef(), services, response);
	}


	@Override
	public EmsScriptNode createFolder(String name) {
		return new EmsScriptNode(super.createFolder(name).getNodeRef(), services, response, status);
	}


	@Override
	public EmsScriptNode createFolder(String name, String type) {
		return new EmsScriptNode(super.createFolder(name, type).getNodeRef(), services, response, status);
	}


	/**
	 * Check whether or not a node has the specified aspect, add it if not
	 * @param string	Short name (e.g., sysml:View) of the aspect to look for
	 * @return			true if node updated with aspect
	 */
	public boolean createOrUpdateAspect(String aspect) {
		if (!hasAspect(aspect)) {
			log(getName() + ": " + aspect + " aspect added");
			return addAspect(aspect);
		}
		return false;
	}

	
	/**
	 * Check whether an association exists of the specified type between source and target, create/update as necessary
	 * TODO: updating associations only works for singular associations, need to expand to multiple
	 * NOTE: do not use for child associations
	 * @param target	Target node of the association
	 * @param type		Short name of the type of association to create 
	 * @return			true if association updated or created
	 */
	public boolean createOrUpdateAssociation(ScriptNode target, String type) {
	    return createOrUpdateAssociation(target, type, false);
	}
	
	
	public boolean createOrUpdateAssociation(ScriptNode target, String type, boolean isMultiple) {
        QName typeQName = createQName(type);
        List<AssociationRef> refs = services.getNodeService().getTargetAssocs(nodeRef, RegexQNamePattern.MATCH_ALL );

        // check all associations to see if there's a matching association
        for (AssociationRef ref: refs) {
            if (ref.getTypeQName().equals(typeQName)) {
                if (ref.getSourceRef() != null && ref.getTargetRef() != null) {
                    if (ref.getSourceRef().equals(nodeRef) && 
                            ref.getTargetRef().equals(target.getNodeRef())) {
                        // found it, no need to update
                        return false; 
                    }
                }
                // TODO: need to check for multiple associations?
                if (!isMultiple) {
                    // association doesn't match, no way to modify a ref, so need to remove then create
                    services.getNodeService().removeAssociation(nodeRef, target.getNodeRef(), typeQName);
                    break;
                }
            }
        }
        
        // Target nodeRef isn't found?
//      log(getName() + ": " + type + " peer association updated, target: " + target.getName());
        services.getNodeService().createAssociation(nodeRef, target.getNodeRef(), typeQName);
        return true;
	}
	
	
	/**
	 * Create a child association between a parent and child node of the specified type
	 * 
	 * // TODO investigate why alfresco repo deletion of node doesn't remove its reified package
	 * 
	 * NOTE: do not use for peer associations
	 * @param child		Child node
	 * @param type		Short name of the type of child association to create
	 * @return			True if updated or created child relationship
	 */
	public boolean createOrUpdateChildAssociation(ScriptNode child, String type) {
		List<ChildAssociationRef> refs = services.getNodeService().getChildAssocs(nodeRef);
		QName typeQName = createQName(type);

		// check all associations to see if there's a matching association
		for (ChildAssociationRef ref: refs) {
			if (ref.getTypeQName().equals(typeQName)) {
				if (ref.getParentRef().equals(nodeRef) && 
						ref.getChildRef().equals(child.getNodeRef())) {
					// found it, no need to update
					return false; 
				} else {
					services.getNodeService().removeChildAssociation(ref);
					break;
				}
			}
		}

		log(getName() + ": " + type + " added child association to child = " + child.getName());
		services.getNodeService().addChild(nodeRef, child.getNodeRef(), typeQName, typeQName);
		return true;		
	}
	
	
	/**
	 * Check whether or not a node has a property, update or create as necessary
	 * 
	 * NOTE: this only works for non-collection properties - for collections handwrite (or see how it's done in ModelPost.java)
	 * @param acmType	Short name for the Alfresco Content Model type
	 * @param value		Value to set property to
	 * @return			true if property updated, false otherwise (e.g., value did not change)
	 */
	public <T extends Serializable> boolean createOrUpdateProperty(String acmType, T value) {
		@SuppressWarnings("unchecked")
		T oldValue = (T) getProperty(acmType);
		if (oldValue != null) {
			if (!value.equals(oldValue)) {
				setProperty(acmType, value);
				log(getName() + ": " + acmType + " property updated to value = " + value);
				return true;
			}
		} else {
			log(getName() + ": " + acmType + " property created with value = " + value);
			setProperty(acmType, value);
		}
		return false;
	}	

	
	/**
	 * Checks and updates properties that have multiple values
	 * @param type		Short name of the content model property to be updated
	 * @param array		New list of values to update
	 * @param valueType	The value type (needed for casting and making things generic)
	 * @return			True if values updated/create, false if unchanged
	 * @throws JSONException
	 */
	@SuppressWarnings("unchecked")
	public <T extends Serializable> boolean createOrUpdatePropertyValues(String type, JSONArray array, T valueType) throws JSONException {
		ArrayList<T> values = new ArrayList<T>();
		for (int ii = 0; ii < array.length(); ii++) {
			values.add((T) array.get(ii));
		}
		
		ArrayList<T> oldValues = (ArrayList<T>) getProperty(type);
		if (!checkIfListsEquivalent(oldValues, values)) {
			log(getName() + ": " + type + " multivalue property updated to " + values);
			setProperty(type, values);
		} else {
			return false;
		}
		
		return true;
	}
	
	/**
	 * Utility to compare lists of node refs to one another
	 * @param x	First list to compare
	 * @param y	Second list to compare
	 * @return	true if same, false otherwise
	 */
	public static <T extends Serializable> boolean checkIfListsEquivalent(ArrayList<T> x, ArrayList<T> y) {
		if (x == null || y == null) {
			return false;
		}
		if (x.size() != y.size()) {
			return false;
		}
		for (int ii = 0; ii < x.size(); ii++) {
			if (!x.get(ii).equals(ii)) {
				return false;
			}
		}
		return true;
	}
	
	
	/**
	 * Override createNode to return an EmsScriptNode
	 * @param name     cm:name of node
	 * @param type     Alfresco Content Model type of node to create
	 * @return         created child EmsScriptNode
	 */
	@Override
	public EmsScriptNode createNode(String name, String type) {
		EmsScriptNode result = null;
//		Date start = new Date(), end; 

		if (!useFoundationalApi) {
			result = new EmsScriptNode(super.createNode(name, type).getNodeRef(), services, response);
		} else {
			Map<QName, Serializable> props = new HashMap<QName, Serializable>(
					1, 1.0f);
			// don't forget to set the name
			props.put(ContentModel.PROP_NAME, name);

			QName typeQName = createQName(type);
			if (typeQName != null) {
    			ChildAssociationRef assoc = services.getNodeService().createNode(
    					nodeRef,
    					ContentModel.ASSOC_CONTAINS,
    					QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI,
    							QName.createValidLocalName(name)),
    					createQName(type), props);
                log("Node " + name + " created");
                result = new EmsScriptNode(assoc.getChildRef(), services, response);            
			} else {
			    log("Could not find type "  + type);
			}
		}
		
//		end = new Date(); System.out.println("\tcreateNode: " + (end.getTime()-start.getTime()));
		return result;
	}

	/**
	 * Return the first AssociationRef of a particular type
	 * @param type	Short name for type to filter on
	 * @return
	 */
	public EmsScriptNode getFirstAssociationByType(String type) {
		List<AssociationRef> assocs = services.getNodeService().getTargetAssocs(nodeRef, RegexQNamePattern.MATCH_ALL);
		if (assocs != null) {
			// check all associations to see if there's a matching association
			for (AssociationRef ref: assocs) {
				if (ref.getTypeQName().equals(createQName(type))) {
					return new EmsScriptNode(ref.getTargetRef(), services, response);
				}
			}
		}
		return null;
	}

	/**
	 * Get list of ChildAssociationRefs
	 * @return
	 */
	public List<ChildAssociationRef> getChildAssociationRefs() {
		return services.getNodeService().getChildAssocs(nodeRef);
	}
	
	
	@Override
	public String getName() {
		return (String)getProperty(Acm.ACM_CM_NAME);
	}


	@Override
	public EmsScriptNode getParent() {
		return new EmsScriptNode(super.getParent().getNodeRef(), services, response);
	}


	/**
	 * Get the property of the specified type
	 * @param acmType	Short name of property to get
	 * @return
	 */
	public Object getProperty(String acmType) {
		if (useFoundationalApi) {
			return services.getNodeService().getProperty(nodeRef, createQName(acmType));
		} else {
			return getProperties().get(acmType);
		}
	}

	
	public StringBuffer getResponse() {
		return response;
	}


	public Status getStatus() {
		return status;
	}

	/**
	 * Append onto the response for logging purposes
	 * @param msg	Message to be appened to response
	 * TODO: fix logger for EmsScriptNode
	 */
	public void log(String msg) {
//		if (response != null) {
//			response.append(msg + "\n");
//		}
	}

	
	/**
	 * Genericized function to set property for non-collection types
	 * @param acmType  Property short name for alfresco content model type 
	 * @param value    Value to set property to
	 */
	public <T extends Serializable >void setProperty(String acmType, T value) {
		if (useFoundationalApi) {
			log(getName() + ": " + acmType + " property set to " + value);
			services.getNodeService().setProperty(nodeRef, createQName(acmType), value);
		} else {
			getProperties().put(acmType, value);
			save();
		}
	}
	
	
	public void setResponse(StringBuffer response) {
		this.response = response;
	}

	
	public void setStatus(Status status) {
		this.status = status;
	}
	
	/**
	 * Gets the SysML qualified name for an object - if not SysML, won't return anything
	 * @return SysML qualified name (e.g., sysml:name qualified)
	 */
	public String getSysmlQName() {
        StringBuffer qname = new StringBuffer();

        NodeService nodeService = services.getNodeService();
        Path path = nodeService.getPath(this.getNodeRef());
        Iterator<Path.Element> pathElements = path.iterator();
        while (pathElements.hasNext()) {
            Path.Element pathElement = pathElements.next();
            if (pathElement instanceof ChildAssocElement) {
                   ChildAssociationRef elementRef = ((ChildAssocElement)pathElement).getRef();
                    if (elementRef.getParentRef() != null)
                    {
                        Serializable nameProp = null;
                        nameProp = nodeService.getProperty(elementRef.getChildRef(), QName.createQName(Acm.ACM_NAME, services.getNamespaceService()));
                        if (nameProp != null) {
                            // use the name property if we are allowed access to it
                            qname.append("/" + nameProp.toString());
                        }
                    }
            }
        }

        return qname.toString();
	}
	

	/**
	 * Get the children views as a JSONArray
	 * @return
	 */
	public JSONArray getChildrenViewsJSONArray() {
	    JSONArray childrenViews = new JSONArray();
        try {
            Object property = this.getProperty(Acm.ACM_CHILDREN_VIEWS);
            if (property != null) {
                childrenViews = new JSONArray(property.toString());
            }
        } catch (JSONException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

	    return childrenViews;
	}
	
	
    /**
     * Convert node into our custom JSONObject with all possible keys
     * @return                     JSONObject serialization of node
     */
	public JSONObject toJSONObject() throws JSONException {
	    return toJSONObject(Acm.JSON_TYPE_FILTER.ALL);
	}
	
    /**
     * Convert node into our custom JSONObject, showing qualifiedName and editable keys
     * @param renderType           Type of JSONObject to render, this filters what keys are in JSONObject
     * @return                     JSONObject serialization of node
     */
	public JSONObject toJSONObject(Acm.JSON_TYPE_FILTER renderType) throws JSONException {
	    return toJSONObject(renderType, true, true);
	}
	
	/**
	 * Convert node into our custom JSONObject
	 * @param renderType           Type of JSONObject to render, this filters what keys are in JSONObject
	 * @param showQualifiedName    If true, displays qualifiedName key
	 * @param showEditable         If true, displays editable key
	 * @return                     JSONObject serialization of node
	 */
	public JSONObject toJSONObject(Acm.JSON_TYPE_FILTER renderType, boolean showQualifiedName, boolean showEditable) throws JSONException {
	    JSONObject element = new JSONObject();

	    // add in all the properties
        for (String acmType: Acm.ACM2JSON.keySet()) {
            Object elementValue = this.getProperty(acmType);
            if (elementValue != null) {
                String jsonType = Acm.ACM2JSON.get(acmType);
                if (Acm.JSON_FILTER_MAP.get(renderType).contains(jsonType)) {
                    if (Acm.JSON_ARRAYS.contains(jsonType)) {
                        String elementString = elementValue.toString();
                        elementString = fixArtifactUrls(elementString, true);
                        element.put(jsonType, new JSONArray(elementString));
                    } else {
                        if (elementValue instanceof String) {
                            String elementString = (String) elementValue;
                            element.put(jsonType, fixArtifactUrls(elementString, false));
                        } else if (elementValue instanceof Date) {
                            DateTime dt = new DateTime((Date) elementValue);
                            DateTimeFormatter fmt = ISODateTimeFormat.dateTime();
                            element.put(jsonType, fmt.print(dt));
                        } else {
                            element.put(jsonType, elementValue);
                        }
                    }
                }
            }
        }

        // add in content type
        if (Acm.JSON_FILTER_MAP.get(renderType).contains(Acm.JSON_TYPE)) {
            element.put(Acm.JSON_TYPE,  this.getQNameType().getLocalName());
        }
        
        // add in property type(s)
        if (Acm.JSON_FILTER_MAP.get(renderType).contains(Acm.JSON_PROPERTY_TYPE)) {
            JSONArray propertyTypes = getTargetAssocsIdsByType(Acm.ACM_PROPERTY_TYPE);
            if (propertyTypes.length() > 0) {
                element.put(Acm.JSON_PROPERTY_TYPE, propertyTypes.get(0));
            }
        }

        // add in value and value types
        if (Acm.JSON_FILTER_MAP.get(renderType).contains(Acm.JSON_VALUE_TYPE)) {
            Object valueType = this.getProperty(Acm.ACM_VALUE_TYPE);
            if (valueType != null) {
                if (valueType.equals(Acm.JSON_ELEMENT_VALUE)) {
                    @SuppressWarnings("unchecked")
                    List<NodeRef> elementValue = (List<NodeRef>) this.getProperty(Acm.ACM_ELEMENT_VALUE);
                    if (elementValue != null) {
                        JSONArray array = new JSONArray();
                        for (NodeRef evRef: elementValue) {
                            EmsScriptNode ev = new EmsScriptNode(evRef, services, response);
                            array.put(ev.getProperty(Acm.ACM_ID));
                        }
                        element.put("value", array);
                    }
                } else {
                    Object property = this.getProperty(Acm.JSON2ACM.get((String) valueType));
                    if (property != null) {
                        element.put("value",  property);
                    }
                }
                element.put(Acm.JSON_VALUE_TYPE,  valueType);
            }
        }
        
        // add in owner
        if (Acm.JSON_FILTER_MAP.get(renderType).contains(Acm.JSON_OWNER)) {
            EmsScriptNode parent = this.getParent();
            if (parent != null) {
                element.put(Acm.JSON_OWNER,  parent.getName().replace("_pkg", ""));
            }
        }

        // add comment
        if (Acm.JSON_FILTER_MAP.get(renderType).contains(Acm.JSON_COMMENT)){ 
            JSONArray annotatedElements = getTargetAssocsIdsByType(Acm.ACM_ANNOTATED_ELEMENTS);
            if (annotatedElements.length() > 0) {
                element.put(Acm.JSON_ANNOTATED_ELEMENTS, annotatedElements);
            }
        }
        
        // show qualified name if toggled
        if (showQualifiedName) {
            element.put("qualifiedName",  this.getSysmlQName());
        }
        
        // show editable if toggled
        if (showEditable) {
            element.put("editable", this.hasPermission(PermissionService.WRITE));
        }
        
	    return element;
	}
	
	public JSONArray getTargetAssocsIdsByType(String acmType) {
	    boolean isSource = false;
	    return getAssocsIdsByDirection(acmType, isSource);
	}

    public JSONArray getSourceAssocsIdsByType(String acmType) {
        boolean isSource = true;
        return getAssocsIdsByDirection(acmType, isSource);
    }

    /**
     * Returns a JSONArray of the sysml:ids of the found associations
     * @param acmType
     * @param isSource
     * @return  JSONArray of the sysml:ids found
     */
	protected JSONArray getAssocsIdsByDirection(String acmType, boolean isSource) {
        JSONArray array = new JSONArray();
        List<AssociationRef> assocs;
        if (isSource) {
            assocs = services.getNodeService().getSourceAssocs(nodeRef, RegexQNamePattern.MATCH_ALL);
        } else {
            assocs = services.getNodeService().getTargetAssocs(nodeRef, RegexQNamePattern.MATCH_ALL);
        }
        for (AssociationRef aref: assocs) {
            QName typeQName = createQName(acmType); 
            if (aref.getTypeQName().equals(typeQName)) {
                NodeRef targetRef;
                if (isSource) {
                    targetRef = aref.getSourceRef();
                } else {
                    targetRef = aref.getTargetRef();
                }
                array.put(services.getNodeService().getProperty(targetRef, createQName(Acm.ACM_ID)));
            }
        }
        
        return array;
	}
	

    public List<EmsScriptNode> getTargetAssocsNodesByType(String acmType) {
        boolean isSource = false;
        return getAssocsNodesByDirection(acmType, isSource);
    }

    public List<EmsScriptNode> getSourceAssocsNodesByType(String acmType) {
        boolean isSource = true;
        return getAssocsNodesByDirection(acmType, isSource);
    }

    /**
     * Get a list of EmsScriptNodes of the specified association type
     * @param acmType
     * @param isSource
     * @return
     */
    protected List<EmsScriptNode> getAssocsNodesByDirection(String acmType,
            boolean isSource) {
        List<EmsScriptNode> list = new ArrayList<EmsScriptNode>();
        List<AssociationRef> assocs;
        if (isSource) {
            assocs = services.getNodeService().getSourceAssocs(nodeRef,
                    RegexQNamePattern.MATCH_ALL);
        } else {
            assocs = services.getNodeService().getTargetAssocs(nodeRef,
                    RegexQNamePattern.MATCH_ALL);
        }
        for (AssociationRef aref : assocs) {
            QName typeQName = createQName(acmType);
            if (aref.getTypeQName().equals(typeQName)) {
                NodeRef targetRef;
                if (isSource) {
                    targetRef = aref.getSourceRef();
                } else {
                    targetRef = aref.getTargetRef();
                }
                list.add(new EmsScriptNode(targetRef, services, response));
            }
        }

        return list;
    }	
	
	
	/**
	 * Given an JSONObject, filters it to find the appropriate relationships to be provided into model post
	 * @param jsonObject
	 * @return
	 * @throws JSONException 
	 */
	public static JSONObject filterRelationsJSONObject(JSONObject jsonObject) throws JSONException {
        JSONObject relations = new JSONObject();
        JSONObject elementValues = new JSONObject();
        JSONObject propertyTypes = new JSONObject();
        JSONObject annotatedElements = new JSONObject();
        JSONObject relationshipElements = new JSONObject();
        JSONArray array;

        if (jsonObject.has(Acm.JSON_VALUE_TYPE)) {
            array = jsonObject.getJSONArray("value");
            if (jsonObject.get(Acm.JSON_VALUE_TYPE).equals(Acm.JSON_ELEMENT_VALUE)) {
                elementValues.put(jsonObject.getString(Acm.JSON_ID), array);
            }
        }
        
        if (jsonObject.has(Acm.JSON_PROPERTY_TYPE)) {
            String propertyType = jsonObject.getString(Acm.JSON_PROPERTY_TYPE);
            if (!propertyType.equals("null")) {
                propertyTypes.put(jsonObject.getString(Acm.JSON_ID), propertyType);
            }
        }
        
        if (jsonObject.has(Acm.JSON_SOURCE) && jsonObject.has(Acm.JSON_TARGET)) {
            JSONObject relJson = new JSONObject();
            String source = jsonObject.getString(Acm.JSON_SOURCE);
            String target = jsonObject.getString(Acm.JSON_TARGET);
            relJson.put(Acm.JSON_SOURCE, source);
            relJson.put(Acm.JSON_TARGET, target);
            relationshipElements.put(jsonObject.getString(Acm.JSON_ID), relJson);
        } else if (jsonObject.has(Acm.JSON_ANNOTATED_ELEMENTS)) {
            array = jsonObject.getJSONArray("annotatedElements");
            annotatedElements.put(jsonObject.getString(Acm.JSON_ID), array);
        }

        relations.put("annotatedElements", annotatedElements);
        relations.put("relationshipElements", relationshipElements);
        relations.put("propertyTypes", propertyTypes);
        relations.put("elementValues", elementValues);
        
        return relations;
	}
	
	/**
	 * Update the node with the properties from the jsonObject
	 * @param jsonObject
	 * @throws JSONException 
	 */
	public void ingestJSON(JSONObject jsonObject) throws JSONException {
	    JSONArray array;
	    
	    // fill in all the properties
	    for (String jsonType: Acm.JSON2ACM.keySet()) {
	        String acmType = Acm.JSON2ACM.get(jsonType);
	        if (jsonObject.has(jsonType)) {
	            if (jsonType.equals(Acm.JSON_VIEW_2_VIEW) || jsonType.equals(Acm.JSON_NO_SECTIONS)) {
	                array = jsonObject.getJSONArray(jsonType);
	                this.createOrUpdateProperty(acmType, array.toString());
	            } else {
	                String property = jsonObject.getString(jsonType);
	                if (jsonType.startsWith("is")) {
	                    this.createOrUpdateProperty(acmType, new Boolean(property));
	                } else {
	                    this.createOrUpdateProperty(acmType, new String(property));
	                }
	            }
	        }
	    }
	    
	    // fill in the valueTypes and all relationships
        if (jsonObject.has(Acm.JSON_VALUE_TYPE)) {
            String acmType = Acm.JSON2ACM.get(jsonObject.get(Acm.JSON_VALUE_TYPE));
            array = jsonObject.getJSONArray("value");
            if (acmType.equals(Acm.ACM_LITERAL_BOOLEAN)) {
                this.createOrUpdatePropertyValues(acmType, array, new Boolean(true));
            } else if (acmType.equals(Acm.ACM_LITERAL_INTEGER)) {
                this.createOrUpdatePropertyValues(acmType, array, new Integer(0));
            } else if (acmType.equals(Acm.ACM_LITERAL_REAL)) {
                this.createOrUpdatePropertyValues(acmType, array, new Double(0.0));
            } else if (acmType.equals(Acm.ACM_LITERAL_STRING)) {
                this.createOrUpdatePropertyValues(acmType, array, new String(""));
            }
        }
	}
	
	/**
	 * Wrapper for replaceArtifactUrl with different patterns if necessary
	 * @param content
	 * @param escape
	 * @return
	 */
	public String fixArtifactUrls(String content, boolean escape) {
	    String result = content;
        result = replaceArtifactUrl(result, "src=\\\\\"/editor/images/docgen/", "src=\\\\\"/editor/images/docgen/.*?\\\\\"", escape);
	    
        return result;
	}
	
	/**
	 * Utility method that replaces the image links with references to the repository urls
	 * @param content
	 * @param prefix
	 * @param pattern
	 * @param escape
	 * @return
	 */
	public String replaceArtifactUrl(String content, String prefix, String pattern, boolean escape) {
	    if (content == null) {
	        return content;
	    }
	    
	    String result = content;
	    Pattern p = Pattern.compile(pattern);
	    Matcher matcher = p.matcher(content);
	   
        while (matcher.find()) {
            String filename = matcher.group(0);
            // not sure why this can't be chained correctly
            filename = filename.replace("\"", "");
            filename = filename.replace("_latest", "");
            filename = filename.replace("\\","");
            filename = filename.replace("src=/editor/images/docgen/", "");
            NodeRef nodeRef = findNodeRefByType(filename, "@cm\\:name:\"");
            if (nodeRef != null) {
                NodeRef versionedNodeRef = services.getVersionService().getCurrentVersion(nodeRef).getVersionedNodeRef();
                EmsScriptNode versionedNode = new EmsScriptNode(versionedNodeRef, services, response);
                String nodeurl = "";
                if (prefix.indexOf("src") >= 0) {
                    nodeurl = "src=\\\"";
                }
                // TODO: need to map context out...
                String context = "https://sheldon/alfresco";
                nodeurl += context + versionedNode.getUrl() + "\\\"";
//                if (escape) {
//                    nodeurl = nodeurl.replace("/", "").replace("\\", "\\\"");
//                }
                result = content.replace(matcher.group(0), nodeurl);
            }
        }
        
	    return result;
	}
	
	// TODO: make this utility function - used in AbstractJavaWebscript too
	protected NodeRef findNodeRefByType(String name, String type) {
        ResultSet results = null;
        NodeRef nodeRef = null;
        try {
            results = services.getSearchService().query(SEARCH_STORE, SearchService.LANGUAGE_LUCENE, type + name + "\"");
            if (results != null) {
                for (ResultSetRow row: results) {
                    nodeRef = row.getNodeRef();
                    break ; //Assumption is things are uniquely named - TODO: fix since snapshots have same name?...
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (results != null) {
                results.close();
            }
        }

        return nodeRef;	    
	}
	
	   
    public static class EmsScriptNodeComparator implements Comparator<EmsScriptNode> {
        @Override
        public int compare(EmsScriptNode x, EmsScriptNode y) {
            Date xModified;
            Date yModified;
            
            xModified = (Date) x.getProperty(Acm.ACM_LAST_MODIFIED);
            yModified = (Date) y.getProperty(Acm.ACM_LAST_MODIFIED);
            
            if (xModified == null) {
                return -1;
            } else if (yModified == null) {
                return 1;
            } else {
                return (xModified.compareTo(yModified));
            }
        }
    }

}