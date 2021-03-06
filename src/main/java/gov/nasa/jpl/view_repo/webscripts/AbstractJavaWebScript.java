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

import gov.nasa.jpl.ae.event.Call;
import gov.nasa.jpl.ae.event.ConstraintExpression;
import gov.nasa.jpl.ae.event.Expression;
import gov.nasa.jpl.ae.event.Parameter;
import gov.nasa.jpl.ae.event.ParameterListenerImpl;
import gov.nasa.jpl.ae.solver.Constraint;
import gov.nasa.jpl.ae.solver.ConstraintLoopSolver;
import gov.nasa.jpl.ae.sysml.SystemModelSolver;
import gov.nasa.jpl.ae.sysml.SystemModelToAeExpression;
import gov.nasa.jpl.ae.util.ClassData;
import gov.nasa.jpl.mbee.util.Debug;
import gov.nasa.jpl.mbee.util.Pair;
import gov.nasa.jpl.mbee.util.Random;
import gov.nasa.jpl.mbee.util.Seen;
import gov.nasa.jpl.mbee.util.SeenHashSet;
import gov.nasa.jpl.mbee.util.TimeUtils;
import gov.nasa.jpl.mbee.util.Utils;
import gov.nasa.jpl.view_repo.actions.ActionUtil;
import gov.nasa.jpl.view_repo.util.Acm;
import gov.nasa.jpl.view_repo.util.CommitUtil;
import gov.nasa.jpl.view_repo.util.EmsScriptNode;
import gov.nasa.jpl.view_repo.util.EmsSystemModel;
import gov.nasa.jpl.view_repo.util.EmsTransaction;
import gov.nasa.jpl.view_repo.util.JsonDiffDiff.DiffType;
import gov.nasa.jpl.view_repo.util.NodeUtil;
import gov.nasa.jpl.view_repo.util.NodeUtil.SearchType;
import gov.nasa.jpl.view_repo.util.WorkspaceDiff;
import gov.nasa.jpl.view_repo.util.WorkspaceNode;

import java.util.Formatter;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.*;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONString;
import org.alfresco.repo.jscript.ScriptNode;
import org.alfresco.repo.model.Repository;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.site.SiteInfo;
import org.alfresco.service.cmr.site.SiteVisibility;
import org.apache.log4j.*;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;


/**
 * Base class for all EMS Java backed webscripts. Provides helper functions and
 * key variables necessary for execution. This provides most of the capabilities
 * that were in utils.js
 *
 * @author cinyoung
 *
 */
public abstract class AbstractJavaWebScript extends DeclarativeJavaWebScript {
    private static Logger logger = Logger.getLogger(AbstractJavaWebScript.class);
    // FIXME -- Why is this not static? Concurrent webscripts with different
    // loglevels will interfere with each other.
    public Level logLevel = Level.WARN;
    
    public Formatter formatter = new Formatter ();
    /*public enum LogLevel {
		DEBUG(0), INFO(1), WARNING(2), ERROR(3);
		private int value;
		private LogLevel(int value) {
			this.value = value;
		}
	}*/

    public static final int MAX_PRINT = 200;

    public static boolean defaultRunWithoutTransactions = false;

    // injected members
	protected ServiceRegistry services;		// get any of the Alfresco services
	protected Repository repository;		// used for lucene search

	// internal members
    // when run in background as an action, this needs to be false
    public boolean runWithoutTransactions = defaultRunWithoutTransactions;
    //public UserTransaction trx = null;
	protected ScriptNode companyhome;
	protected Map<String, EmsScriptNode> foundElements = new LinkedHashMap<String, EmsScriptNode>();
    protected Map<String, EmsScriptNode> movedAndRenamedElements = new LinkedHashMap<String, EmsScriptNode>();

	// needed for Lucene search
	protected static final StoreRef SEARCH_STORE = new StoreRef(StoreRef.PROTOCOL_WORKSPACE, "SpacesStore");

    // response to HTTP request, made as class variable so all methods can update
    protected StringBuffer response = new StringBuffer();
    protected Status responseStatus = new Status();

    protected WorkspaceDiff wsDiff;

    public static boolean alwaysTurnOffDebugOut = true;

    // keeps track of who made the call to the service
    protected String source = null;
    protected EmsSystemModel systemModel;
    protected SystemModelToAeExpression< EmsScriptNode, EmsScriptNode, String, Object, EmsSystemModel > sysmlToAe;
    protected static SystemModelToAeExpression< EmsScriptNode, EmsScriptNode, String, Object, EmsSystemModel > globalSysmlToAe;

    protected void initMemberVariables(String siteName) {
		companyhome = new ScriptNode(repository.getCompanyHome(), services);
	}

	public void setRepositoryHelper(Repository repositoryHelper) {
	    if ( repositoryHelper == null ) return;
		this.repository = repositoryHelper;
	}

	public void setServices(ServiceRegistry registry) {
        if ( registry == null ) return;
		this.services = registry;
	}

	public AbstractJavaWebScript( Repository repository,
                                  ServiceRegistry services,
                                  StringBuffer response ) {
        this.setRepositoryHelper( repository );
        this.setServices( services );
        this.response = response ;
        // TODO -- set maximum log level; Overrides that specified in log4j.properties (I THINK)
        // FIXME -- Why is this not static?
        logger.setLevel(logLevel);
    }

    public AbstractJavaWebScript(Repository repositoryHelper, ServiceRegistry registry) {
        this.setRepositoryHelper(repositoryHelper);
        this.setServices(registry);
        // TODO -- set maximum log level; Overrides that specified in log4j.properties (I THINK)
        // FIXME -- Why is this not static?
        logger.setLevel(logLevel);
    }

    public AbstractJavaWebScript() {
        // default constructor for spring
        super();
        // TODO -- set maximum log level; Overrides that specified in log4j.properties (I THINK)
        // FIXME -- Why is this not static?
        logger.setLevel(logLevel);
    }
    
    /**
	 * Utility for clearing out caches
	 * TODO: do we need to clear caches if Spring isn't making singleton instances
	 */
    protected void clearCaches() {
        clearCaches( true );
    }
	protected void clearCaches( boolean resetTransactionState ) {
	    if ( resetTransactionState ) {
            NodeUtil.setBeenInsideTransaction( false );
            NodeUtil.setBeenOutsideTransaction( false );
            NodeUtil.setInsideTransactionNow( false );
	    }
	    
		foundElements = new HashMap<String, EmsScriptNode>();
		response = new StringBuffer();
		responseStatus.setCode(HttpServletResponse.SC_OK);
        NodeUtil.initHeisenCache();
        if ( alwaysTurnOffDebugOut  ) {
            Debug.turnOff();
        }
	}
	
    protected void cleanJsonCache() {
        Map< String, EmsScriptNode > nodesToClean = new LinkedHashMap< String, EmsScriptNode >();
        
        Seen< String > seen = new SeenHashSet< String >();
        for ( EmsScriptNode node : foundElements.values() ) {
            if ( node.renamed || node.moved ) {
                String sysmlId = node.getSysmlId();
                collectChildNodesToClean( sysmlId, node, nodesToClean, seen );
            }
        }
        
        for ( EmsScriptNode node : nodesToClean.values() ) {
            node.removeFromJsonCache( false );
        }
    }
    
    protected void collectChildNodesToClean( String id, EmsScriptNode node,
                                             Map< String, EmsScriptNode > nodesToClean,
                                             Seen< String > seen ) {
        //String sysmlId = node.getSysmlId();
        
        Pair< Boolean, Seen< String > > p = Utils.seen( id, true, seen );
        if ( p.first ) return;
        seen = p.second;

        ArrayList< NodeRef > children = node.getOwnedChildren( true, null, null );
        for ( NodeRef ref : children ) {
            EmsScriptNode childNode = new EmsScriptNode( ref, getServices() );
            String sysmlId = childNode.getSysmlId();
            if ( foundElements.containsKey( sysmlId ) ) continue;
            if ( nodesToClean.containsKey( sysmlId ) ) continue;
            nodesToClean.put( sysmlId, childNode );
            collectChildNodesToClean( sysmlId, childNode, nodesToClean, seen );
        }

    }

    abstract protected Map< String, Object > executeImplImpl( final WebScriptRequest req,
                                                              final Status status,
                                                              final Cache cache );
    
    protected Map< String, Object > executeImplImpl( final WebScriptRequest req,
                                                     final Status status, final Cache cache,
                                                     boolean withoutTransactions ) {
        clearCaches( true );
        clearCaches(); // calling twice for those redefine clearCaches() to
                       // always call clearCaches( false )
        final Map< String, Object > model = new HashMap<String, Object>();
        new EmsTransaction( getServices(), getResponse(), getResponseStatus(), withoutTransactions ) {
            @Override
            public void run() throws Exception {
                Map< String, Object > m = executeImplImpl( req, status, cache );
                if ( m != null ) {
                    model.putAll( m );
                }
            }
        };
//            UserTransaction trx;
//            trx = services.getTransactionService().getNonPropagatingUserTransaction();
//            try {
//                trx.begin();
//                NodeUtil.setInsideTransactionNow( true );
//            } catch ( Throwable e ) {
//                String msg = null;
//                tryRollback( trx, e, msg );
//            }
        //Map<String, Object> model = new HashMap<String, Object>();
        if ( !model.containsKey( "res" ) && response != null && response.toString().length() > 0 ) {
            model.put( "res", response.toString() );
        }
        // need to check if the transaction resulted in rollback, if so change the status code
        // TODO: figure out how to get the response message in (response is always empty)
        if (getResponseStatus().getCode() != HttpServletResponse.SC_ACCEPTED) {
            status.setCode( getResponseStatus().getCode() );
        }
        return model;
    }


	/**
	 * Parse the request and do validation checks on request
	 * TODO: Investigate whether or not to deprecate and/or remove
	 * @param req		Request to be parsed
	 * @param status	The status to be returned for the request
	 * @return			true if request valid and parsed, false otherwise
	 */
	abstract protected boolean validateRequest(WebScriptRequest req, Status status);

    /**
     * Get site by name, workspace, and time
     *
     * @param siteName
     *            short name of site
     * @param workspace
     *            the workspace of the version of the site to return
     * @param dateTime
     *            the point in time for the version of the site to return
     * @return
     */
    protected EmsScriptNode getSiteNode(String siteName, WorkspaceNode workspace,
                                        Date dateTime ) {
        return getSiteNode( siteName, workspace, dateTime, true );
    }
    protected EmsScriptNode getSiteNode(String siteName, WorkspaceNode workspace,
                                        Date dateTime, boolean errorOnNull) {
        return getSiteNodeImpl(siteName, workspace, dateTime, false, errorOnNull);
    }

    /**
     * Helper method for getSideNode* methods
     *
     * @param siteName
     * @param workspace
     * @param dateTime
     * @param forWorkspace
     * @return
     */
    private EmsScriptNode getSiteNodeImpl(String siteName, WorkspaceNode workspace,
            						 	   Date dateTime, boolean forWorkspace, boolean errorOnNull) {

		EmsScriptNode siteNode = null;

		if (siteName == null) {
		    if ( errorOnNull ) log(Level.ERROR, HttpServletResponse.SC_BAD_REQUEST,"No sitename provided" );
		} else {
			if (forWorkspace) {
				siteNode = NodeUtil.getSiteNodeForWorkspace( siteName, false, workspace, dateTime,
     											 			 services, response );
			}
			else {
				siteNode = NodeUtil.getSiteNode( siteName, false, workspace, dateTime,
			                 					services, response );
			}
	        if ( errorOnNull && siteNode == null ) {	            
	            log(Level.ERROR,  HttpServletResponse.SC_BAD_REQUEST, "Site node is null");
	        }
		}

		return siteNode;
	}

    /**
     * Get site by name, workspace, and time.  This also checks that the returned node is
     * in the specified workspace, not just whether its in the workspace or any of its parents.
     *
     * @param siteName
     *            short name of site
     * @param workspace
     *            the workspace of the version of the site to return
     * @param dateTime
     *            the point in time for the version of the site to return
     * @return
     */
    protected EmsScriptNode getSiteNodeForWorkspace(String siteName, WorkspaceNode workspace,
                                                    Date dateTime) {
        return getSiteNode( siteName, workspace, dateTime, true );
    }
    protected EmsScriptNode getSiteNodeForWorkspace(String siteName, WorkspaceNode workspace,
                                        			Date dateTime, boolean errorOnNull) {

        return getSiteNodeImpl(siteName, workspace, dateTime, true, errorOnNull);
    }

    protected EmsScriptNode getSiteNodeFromRequest(WebScriptRequest req, boolean errorOnNull) {
        String siteName = null;
        // get timestamp if specified
        String timestamp = req.getParameter("timestamp");
        Date dateTime = TimeUtils.dateFromTimestamp( timestamp );

        WorkspaceNode workspace = getWorkspace( req );

        String[] siteKeys = {"id", "siteId", "siteName"};

        for (String siteKey: siteKeys) {
            siteName = req.getServiceMatch().getTemplateVars().get( siteKey );
            if (siteName != null) break;
        }

        return getSiteNode( siteName, workspace, dateTime, errorOnNull );
    }

	/**
	 * Find node of specified name (returns first found) - so assume uniquely named ids - this checks sysml:id rather than cm:name
	 * This does caching of found elements so they don't need to be looked up with a different API each time.
	 * This also checks that the returned node is in the specified workspace, not just whether its in the workspace
	 * or any of its parents.
	 *
	 * @param id	Node id to search for
	 * @param workspace
     * @param dateTime
	 * @return		ScriptNode with name if found, null otherwise
	 */
	protected EmsScriptNode findScriptNodeByIdForWorkspace(String id,
	                                           				WorkspaceNode workspace,
	                                           				Date dateTime, boolean findDeleted) {
		return NodeUtil.findScriptNodeByIdForWorkspace( id, workspace, dateTime, findDeleted,
	                                        			services, response );
	}

	/**
	 * Find node of specified name (returns first found) - so assume uniquely named ids - this checks sysml:id rather than cm:name
	 * This does caching of found elements so they don't need to be looked up with a different API each time.
	 *
	 * TODO extend so search context can be specified
	 * @param id	Node id to search for
	 * @param workspace
     * @param dateTime
	 * @return		ScriptNode with name if found, null otherwise
	 */
	protected EmsScriptNode findScriptNodeById(String id,
	                                           WorkspaceNode workspace,
	                                           Date dateTime, boolean findDeleted) {
	    return findScriptNodeById( id, workspace, dateTime, findDeleted, null );
	}

	/**
     * Find node of specified name (returns first found) - so assume uniquely named ids - this checks sysml:id rather than cm:name
     * This does caching of found elements so they don't need to be looked up with a different API each time.
     *
     * TODO extend so search context can be specified
     * @param id    Node id to search for
     * @param workspace
     * @param dateTime
     * @return      ScriptNode with name if found, null otherwise
     */
    protected EmsScriptNode findScriptNodeById(String id,
                                               WorkspaceNode workspace,
                                               Date dateTime, boolean findDeleted,
                                               String siteName) {
        return NodeUtil.findScriptNodeById( id, workspace, dateTime, findDeleted,
                                            services, response, siteName );
    }

	/**
     * Find nodes of specified sysml:name
     *
     */
    protected ArrayList<EmsScriptNode> findScriptNodesBySysmlName(String name,
                                                       WorkspaceNode workspace,
                                                       Date dateTime, boolean findDeleted) {
        return NodeUtil.findScriptNodesBySysmlName( name, false, workspace, dateTime, services, findDeleted, false );
    }
    
    
    // Updated log methods with log4j methods (still works with old log calls)
    // String concatenation replaced with C formatting; only for calls with parameters
    protected void log (Level level, int code, String msg, Object...params) {
    	if (level.toInt() >= logger.getLevel().toInt()) {
    		String formattedMsg = formatMessage(msg,params);
    		//String formattedMsg = formatter.format (msg,params).toString();
    		log (level,code,formattedMsg);
    	}
	}
    
    // If no need for string formatting (calls with no string concatenation)
    protected void log(Level level, int code, String msg) {
        String levelMessage = addLevelInfoToMsg (level,msg); 
        updateResponse(code, levelMessage);
		if (level.toInt() >= logger.getLevel().toInt()) {
			// print to response stream if >= existing log level
			log (level, levelMessage);
		}
	}

    // only logging loglevel and a message (no code)
	protected void log(Level level, String msg, Object...params) {
	    if (level.toInt() >= logger.getLevel().toInt()) {
        	String formattedMsg = formatMessage(msg,params); //formatter.format (msg,params).toString();
        	String levelMessage = addLevelInfoToMsg (level,formattedMsg);
        	//TODO: unsure if need to call responseStatus.setMessage(...) since there is no code
        	response.append(levelMessage);
        	log (level, levelMessage);
	    }
	}

	// only logging code and a message (no loglevel, and thus, no check for log level status)
	protected void log(int code, String msg, Object...params) {
		String formattedMsg = formatMessage(msg,params); //formatter.format (msg,params).toString();
		updateResponse (code,formattedMsg);
	}
	
	protected void log(String msg, Object...params) {
		String formattedMsg = formatMessage(msg,params); //formatter.format (msg,params).toString();
		log (formattedMsg);
	}
	
	protected void updateResponse ( int code, String msg) {
		response.append(msg);
		responseStatus.setCode(code);
		responseStatus.setMessage(msg);
	}

	protected void log(String msg) {
	    response.append(msg + "\n");
	    //TODO: add to responseStatus too (below)?
	    //responseStatus.setMessage(msg);
	}

	protected static void log(Level level, String msg) {
	    switch(level.toInt()) {
	        case Level.FATAL_INT:
	            logger.fatal(msg);
	            break;
	        case Level.ERROR_INT:
	            logger.error( msg );
	            break;
	        case Level.WARN_INT:
	            logger.warn(msg);
	            break;
	        case Level.INFO_INT:
	            logger.info( msg );
	            break;
	        case Level.DEBUG_INT:	
	            if (Debug.isOn()){ logger.debug( msg );}
	            break;
            default:
                // TODO: investigate if this the default thing to do
            	if (Debug.isOn()){ logger.debug( msg ); }
	            break;
	    }
	}
	
	protected String addLevelInfoToMsg (Level level, String msg){
		if (level.toInt() != Level.WARN_INT){
			return String.format("[%s]: %s\n",level.toString(),msg);
		}
		else{
			return String.format("[WARNING]: %s\n",msg);
		}
		
	}
	
	// formatMessage function is used to catch certain objects that must be dealt with individually
	// formatter.format() is avoided because it applies toString() directly to objects which provide unreadable outputs
	protected String formatMessage (String initMsg,Object...params){
		String formattedMsg = initMsg;
		Pattern p = Pattern.compile("(%s)");
		Matcher m = p.matcher(formattedMsg);
		
		for (Object obj: params){
			if (obj != null && obj.getClass().isArray()){
				String arrString = "";
				if (obj instanceof int []) { arrString = Arrays.toString((int [])obj);}
				else if (obj instanceof double []) { arrString = Arrays.toString((double [])obj);}
				else if (obj instanceof float []) { arrString = Arrays.toString((float [])obj);}
				else if (obj instanceof boolean []) { arrString = Arrays.toString((boolean [])obj);}
				else if (obj instanceof char []) { arrString = Arrays.toString((char [])obj);}
				else {arrString = Arrays.toString((Object[])obj);}
				formattedMsg = m.replaceFirst(arrString);
			}
			else { // captures Timer, EmsScriptNode, Date, primitive types, NodeRef, JSONObject type objects; applies toString() on all
				formattedMsg = m.replaceFirst(obj == null ? "null" : obj.toString());
			}
			m = p.matcher(formattedMsg);
//			if (obj.getClass().isArray()){	
//				Arrays.toString(obj);
//				String formattedString = m.replaceFirst(o)
//			}
			
		}
		return formattedMsg;
	}
	
	/**
	 * Checks whether user has permissions to the node and logs results and status as appropriate
	 * @param node         EmsScriptNode to check permissions on
	 * @param permissions  Permissions to check
	 * @return             true if user has specified permissions to node, false otherwise
	 */
	protected boolean checkPermissions(EmsScriptNode node, String permissions) {
	    if (node != null) {
	        return node.checkPermissions( permissions, response, responseStatus );
	    } else {
	        return false;
	    }
	}


//	/**
//	 * Checks whether user has permissions to the nodeRef and logs results and status as appropriate
//	 * @param nodeRef      NodeRef to check permissions againts
//	 * @param permissions  Permissions to check
//	 * @return             true if user has specified permissions to node, false otherwise
//	 */
//	protected boolean checkPermissions(NodeRef nodeRef, String permissions) {
//		if (services.getPermissionService().hasPermission(nodeRef, permissions) != AccessStatus.ALLOWED) {
//			log(LogLevel.WARNING, "No " + permissions + " priveleges to " + nodeRef.toString() + ".\n", HttpServletResponse.SC_BAD_REQUEST);
//			return false;
//		}
//		return true;
//	}


    protected static final String WORKSPACE_ID = "workspaceId";
	protected static final String PROJECT_ID = "projectId";
	protected static final String ARTIFACT_ID = "artifactId";
    protected static final String SITE_NAME = "siteName";
    protected static final String SITE_NAME2 = "siteId";
    protected static final String WORKSPACE1 = "workspace1";
    protected static final String WORKSPACE2 = "workspace2";
    protected static final String TIMESTAMP1 = "timestamp1";
    protected static final String TIMESTAMP2 = "timestamp2";

    public static final String NO_WORKSPACE_ID = "master"; // default is master if unspecified
    public static final String NO_PROJECT_ID = "no_project";
    public static final String NO_SITE_ID = "no_site";
    
    
    public String getSiteName( WebScriptRequest req ) {
        return getSiteName( req, false );
    }
    public String getSiteName( WebScriptRequest req, boolean createIfNonexistent ) {
        String runAsUser = AuthenticationUtil.getRunAsUser();
        boolean changeUser = !EmsScriptNode.ADMIN_USER_NAME.equals( runAsUser );
        if ( changeUser ) {
            AuthenticationUtil.setRunAsUser( EmsScriptNode.ADMIN_USER_NAME );
        }

        String siteName = req.getServiceMatch().getTemplateVars().get(SITE_NAME);
        if ( siteName == null ) {
            siteName = req.getServiceMatch().getTemplateVars().get(SITE_NAME2);
        }
        if ( siteName == null || siteName.length() <= 0 ) {
            siteName = NO_SITE_ID;
        }

        if ( changeUser ) {
            AuthenticationUtil.setRunAsUser( runAsUser );
        }

        if ( createIfNonexistent ) {
            WorkspaceNode workspace = getWorkspace( req );
            createSite( siteName, workspace );
        }
        return siteName;
    }

    public static EmsScriptNode getSitesFolder( WorkspaceNode workspace ) {
        EmsScriptNode sitesFolder = null;
        // check and see if the Sites folder already exists
        NodeRef sitesNodeRef = NodeUtil.findNodeRefByType( "Sites", SearchType.CM_NAME, false,
                                                           workspace, null, true, NodeUtil.getServices(), false );
        if ( sitesNodeRef != null ) {
            sitesFolder = new EmsScriptNode( sitesNodeRef, NodeUtil.getServices() );

            // If workspace of sitesNodeRef is this workspace then no need to
            // replicate, otherwise replicate from the master workspace:
            if ( NodeUtil.exists(sitesFolder) && NodeUtil.exists( workspace )
                 && !workspace.equals( sitesFolder.getWorkspace() ) ) {
                sitesFolder = workspace.replicateWithParentFolders( sitesFolder );
            }

        }
        // This case should never occur b/c the master workspace will always
        // have a Sites folder:
        else {
            Debug.error( "Can't find Sites folder in the workspace " + workspace );
        }
        return sitesFolder;
//        EmsScriptNode sf = NodeUtil.getCompanyHome( getServices() ).childByNamePath( "Sites" );
//        return sf;
    }

    public EmsScriptNode createSite( String siteName, WorkspaceNode workspace ) {

        EmsScriptNode siteNode = getSiteNode( siteName, workspace, null, false );
        if (workspace != null) return siteNode; // sites can only be made in master
        boolean validWorkspace = workspace != null && workspace.exists();
        boolean invalidSiteNode = siteNode == null || !siteNode.exists();

        // Create a alfresco Site if creating the site on the master and if the site does not exists:
        if ( invalidSiteNode && !validWorkspace ) {
            NodeUtil.transactionCheck( logger, null );
            SiteInfo foo = services.getSiteService().createSite( siteName, siteName, siteName, siteName, SiteVisibility.PUBLIC );
            siteNode = new EmsScriptNode( foo.getNodeRef(), services );
            siteNode.createOrUpdateAspect( "cm:taggable" );
            siteNode.createOrUpdateAspect(Acm.ACM_SITE);
            // this should always be in master so no need to check workspace
//            if (workspace == null) { // && !siteNode.getName().equals(NO_SITE_ID)) {
                // default creation adds GROUP_EVERYONE as SiteConsumer, so remove
                siteNode.removePermission( "SiteConsumer", "GROUP_EVERYONE" );
                if ( siteNode.getName().equals(NO_SITE_ID)) {
                    siteNode.setPermission( "SiteCollaborator", "GROUP_EVERYONE" );
                }
//            }
        }

        // If this site is supposed to go into a non-master workspace, then create the site folders
        // there if needed:
        if ( validWorkspace &&
        	( invalidSiteNode || (!invalidSiteNode && !workspace.equals(siteNode.getWorkspace())) ) ) {

	        EmsScriptNode sitesFolder = getSitesFolder(workspace);

	        // Now, create the site folder:
	        if (sitesFolder == null ) {
	            Debug.error("Could not create site " + siteName + "!");
	        } else {
	            siteNode = sitesFolder.createFolder( siteName, null, !invalidSiteNode ? siteNode.getNodeRef() : null );
	            if ( siteNode != null ) siteNode.getOrSetCachedVersion();
	        }
        }

        return siteNode;
    }

    public String getProjectId( WebScriptRequest req, String siteName ) {
        String projectId = req.getServiceMatch().getTemplateVars().get(PROJECT_ID);
        if ( projectId == null || projectId.length() <= 0 ) {
            if (siteName == null) {
                siteName = NO_SITE_ID;
            }
            projectId = siteName + "_" + NO_PROJECT_ID;
        }
        return projectId;
    }

    public static String getWorkspaceId( WebScriptRequest req ) {
        String workspaceId = req.getServiceMatch().getTemplateVars().get(WORKSPACE_ID);
        if ( workspaceId == null || workspaceId.length() <= 0 ) {
            workspaceId = NO_WORKSPACE_ID;
        }
        return workspaceId;
    }
    
    private static String getWorkspaceNum( WebScriptRequest req, boolean isWs1 ) {
        String key = isWs1 ? WORKSPACE1 : WORKSPACE2;
        String workspaceId = req.getServiceMatch().getTemplateVars().get(key);
        if ( workspaceId == null || workspaceId.length() <= 0 ) {
            workspaceId = NO_WORKSPACE_ID;
        }
        return workspaceId;
    }
    
    private static String getTimestampNum( WebScriptRequest req, boolean isTs1 ) {
        String key = isTs1 ? TIMESTAMP1 : TIMESTAMP2;
        String timestamp = req.getServiceMatch().getTemplateVars().get(key);
        if ( timestamp == null || timestamp.length() <= 0 ) {
            timestamp = WorkspaceDiff.LATEST_NO_TIMESTAMP;
        }
        return timestamp;
    }
    
    public static String getWorkspace1( WebScriptRequest req) {
        return getWorkspaceNum(req, true);
    }

    public static String getWorkspace2( WebScriptRequest req) {
        return getWorkspaceNum(req, false);
    }
    
    public static String getTimestamp1( WebScriptRequest req) {
        return getTimestampNum(req, true);
    }

    public static String getTimestamp2( WebScriptRequest req) {
        return getTimestampNum(req, false);
    }
    
    public static String getArtifactId( WebScriptRequest req ) {
        String artifactId = req.getServiceMatch().getTemplateVars().get(ARTIFACT_ID);
        if ( artifactId == null || artifactId.length() <= 0 ) {
        	artifactId = null;
        }
        return artifactId;
    }

    public WorkspaceNode getWorkspace( WebScriptRequest req ) {
        return getWorkspace( req, //false,
                             null );
    }

    public WorkspaceNode getWorkspace( WebScriptRequest req,
//                                       boolean createIfNotFound,
                                       String userName ) {
        return getWorkspace( req, services, response, responseStatus, //createIfNotFound,
                             userName );
    }

    public static WorkspaceNode getWorkspace( WebScriptRequest req,
                                              ServiceRegistry services,
                                              StringBuffer response,
                                              Status responseStatus,
                                              //boolean createIfNotFound,
                                              String userName ) {
        String nameOrId = getWorkspaceId( req );
        return WorkspaceNode.getWorkspaceFromId( nameOrId, services, response, responseStatus,
                                   //createIfNotFound,
                                   userName );
    }

    public ServiceRegistry getServices() {
        if ( services == null ) {
            services = NodeUtil.getServices();
        }
        return services;
    }

    protected boolean checkRequestContent(WebScriptRequest req) {
        if (req.getContent() == null) {
            log(Level.ERROR,  HttpServletResponse.SC_NO_CONTENT, "No content provided.\n");
            return false;
        }
        return true;
    }
    
    /**
     * Returns true if the user has permission to do workspace operations, which is determined
     * by the LDAP group or if the user is admin.
     * 
     */
    protected boolean userHasWorkspaceLdapPermissions() {
        
        if (!NodeUtil.userHasWorkspaceLdapPermissions()) {
            log(Level.ERROR, HttpServletResponse.SC_FORBIDDEN, "User %s does not have LDAP permissions to perform workspace operations.  LDAP group with permissions: %s", 
            		NodeUtil.getUserName(), NodeUtil.getWorkspaceLdapGroup());
            return false;
        }
        return true;
    }


	protected boolean checkRequestVariable(Object value, String type) {
		if (value == null) {
			log(Level.ERROR, HttpServletResponse.SC_BAD_REQUEST, "%s not found.\n",type);
			return false;
		}
		return true;
	}

	/**
	 * Perform Lucene search for the specified pattern and ACM type
	 * TODO: Scope Lucene search by adding either parent or path context
	 * @param type		escaped ACM type for lucene search: e.g. "@sysml\\:documentation:\""
	 * @param pattern   Pattern to look for
	 */
	protected Map<String, EmsScriptNode> searchForElements(String type,
	                                                       String pattern,
	                                                       boolean ignoreWorkspace,
	                                                       WorkspaceNode workspace,
	                                                       Date dateTime) {
		return this.searchForElements( type, pattern, ignoreWorkspace,
		                               workspace, dateTime, null );
	}

	   /**
     * Perform Lucene search for the specified pattern and ACM type for the specified
     * siteName.
     *
     * TODO: Scope Lucene search by adding either parent or path context
     * @param type      escaped ACM type for lucene search: e.g. "@sysml\\:documentation:\""
     * @param pattern   Pattern to look for
     */
    protected Map<String, EmsScriptNode> searchForElements(String type,
                                                                  String pattern,
                                                                  boolean ignoreWorkspace,
                                                                  WorkspaceNode workspace,
                                                                  Date dateTime,
                                                                  String siteName) {

        Map<String, EmsScriptNode> searchResults = new HashMap<String, EmsScriptNode>();

        searchResults.putAll( NodeUtil.searchForElements( type, pattern, ignoreWorkspace,
                                                          workspace,
                                                          dateTime, services,
                                                          response,
                                                          responseStatus,
                                                          siteName) );

        return searchResults;

    }

	/**
     * Helper utility to check the value of a request parameter
     *
     * @param req
     *            WebScriptRequest with parameter to be checked
     * @param name
     *            String of the request parameter name to check
     * @param value
     *            String of the value the parameter is being checked for
     * @return True if parameter is equal to value, False otherwise
     */
    public static boolean checkArgEquals(WebScriptRequest req, String name,
            String value) {
        if (req.getParameter(name) == null) {
            return false;
        }
        return req.getParameter(name).equals(value);
    }

    /**
     * Helper utility to get the value of a Boolean request parameter
     *
     * @param req
     *            WebScriptRequest with parameter to be checked
     * @param name
     *            String of the request parameter name to check
     * @param defaultValue
     *            default value if there is no parameter with the given name
     * @return true if the parameter is assigned no value, if it is assigned
     *         "true" (ignoring case), or if it's default is true and it is not
     *         assigned "false" (ignoring case).
     */
    public static boolean getBooleanArg(WebScriptRequest req, String name,
                                        boolean defaultValue) {
        if ( !Utils.toSet( req.getParameterNames() ).contains( name ) ) {
            return defaultValue;
        }
        String paramVal = req.getParameter(name);
        if ( Utils.isNullOrEmpty( paramVal ) ) return true;
        Boolean b = Utils.isTrue( paramVal, false );
        if ( b != null ) return b;
        return defaultValue;
    }


    public StringBuffer getResponse() {
        return response;
    }

    public Status getResponseStatus() {
        return responseStatus;
    }

    public void setLogLevel(Level level) {
        logLevel = level;
    }

    /**
     * Should create the new instances with the response in constructor, so
     * this can be removed every where
     * @param instance
     */
    public void appendResponseStatusInfo(AbstractJavaWebScript instance) {
        response.append(instance.getResponse());
        responseStatus.setCode(instance.getResponseStatus().getCode());
    }

    protected void printFooter() {
        log( Level.INFO, "*** completed %s %s", ( new Date() ).toString(), getClass().getSimpleName());
    }

    protected void printHeader( WebScriptRequest req ) {
        log( Level.INFO, "*** starting %s %s",( new Date() ).toString(),getClass().getSimpleName() );
        String reqStr = req.getURL();
        log( Level.INFO, "*** request = %s ...", ( reqStr.length() <= MAX_PRINT ? reqStr : reqStr.substring( 0, MAX_PRINT ) ));
    }

    protected static String getIdFromRequest( WebScriptRequest req ) {
        String[] ids = new String[] { "id", "modelid", "modelId", "productid", "productId",
        							  "viewid", "viewId", "workspaceid", "workspaceId",
                                      "elementid", "elementId" };
        String id = null;
        for ( String idv : ids ) {
            id = req.getServiceMatch().getTemplateVars().get(idv);
            if ( id != null ) break;
        }
        if (Debug.isOn()) System.out.println("Got id = " + id);
        if ( id == null ) return null;
        boolean gotElementSuffix  = ( id.toLowerCase().trim().endsWith("/elements") );
        if ( gotElementSuffix ) {
            id = id.substring( 0, id.lastIndexOf( "/elements" ) );
        } else {
            boolean gotViewSuffix  = ( id.toLowerCase().trim().endsWith("/views") );
            if ( gotViewSuffix ) {
                id = id.substring( 0, id.lastIndexOf( "/views" ) );
            }
        }
        if (Debug.isOn()) System.out.println("id = " + id);
        return id;
    }


    protected static boolean urlEndsWith( String url, String suffix ) {
        if ( url == null ) return false;
        url = url.toLowerCase().trim();
        suffix = suffix.toLowerCase().trim();
        if ( suffix.startsWith( "/" ) ) suffix = suffix.substring( 1 );
        int pos = url.lastIndexOf( '/' );
        if (url.substring( pos+1 ).startsWith( suffix ) ) return true;
        return false;
    }
    protected static boolean isDisplayedElementRequest( WebScriptRequest req ) {
        if ( req == null ) return false;
        String url = req.getURL();
        boolean gotSuffix = urlEndsWith( url, "elements" );
        return gotSuffix;
    }

    protected static boolean isContainedViewRequest( WebScriptRequest req ) {
        if ( req == null ) return false;
        String url = req.getURL();
        boolean gotSuffix = urlEndsWith( url, "views" );
        return gotSuffix;
    }


    public void setWsDiff(WorkspaceNode workspace) {
        wsDiff = new WorkspaceDiff(workspace, workspace, response, responseStatus);
    }
    public void setWsDiff(WorkspaceNode workspace1, WorkspaceNode workspace2, Date time1, Date time2, DiffType diffType) {
        wsDiff = new WorkspaceDiff(workspace1, workspace2, time1, time2, response, responseStatus, diffType);
    }

    public WorkspaceDiff getWsDiff() {
        return wsDiff;
    }

    

    
    /**
     * Determines the project site for the passed site node.  Also, determines the
     * site package node if applicable.
     *
     */
    public Pair<EmsScriptNode,EmsScriptNode> findProjectSite(String siteName,
                                                             Date dateTime,
                                                             WorkspaceNode workspace,
                                                             EmsScriptNode initialSiteNode) {        
        String runAsUser = AuthenticationUtil.getRunAsUser();
        boolean changeUser = !EmsScriptNode.ADMIN_USER_NAME.equals( runAsUser );
        if ( changeUser ) {
            AuthenticationUtil.setRunAsUser( EmsScriptNode.ADMIN_USER_NAME );
        }
        Pair< EmsScriptNode, EmsScriptNode > p = 
                findProjectSiteImpl( siteName, dateTime, workspace, initialSiteNode );
        if ( changeUser ) {
            AuthenticationUtil.setRunAsUser( runAsUser );
        }
        return p;
    }
    public Pair<EmsScriptNode,EmsScriptNode> findProjectSiteImpl(String siteName,
                                                                 Date dateTime,
                                                                 WorkspaceNode workspace,
                                                                 EmsScriptNode initialSiteNode) {

        EmsScriptNode sitePackageNode = null;
        EmsScriptNode siteNode = null;

        // If it is a package site, get the corresponding package for the site:
        NodeRef sitePackageRef = (NodeRef) initialSiteNode.getNodeRefProperty( Acm.ACM_SITE_PACKAGE, dateTime,
                                                                               workspace);
        if (sitePackageRef != null) {
            sitePackageNode = new EmsScriptNode(sitePackageRef, services);
        }
        // Could find the package site using the property, try searching for it:
        else if (siteName != null && siteName.startsWith(NodeUtil.sitePkgPrefix)) {

            String[] splitArry = siteName.split(NodeUtil.sitePkgPrefix);
            if (splitArry != null && splitArry.length > 0) {
                String sitePkgName = splitArry[splitArry.length-1];

                sitePackageNode = findScriptNodeById(sitePkgName,workspace, dateTime, false );

                if (sitePackageNode == null) {
                    log(Level.ERROR, HttpServletResponse.SC_NOT_FOUND, "Could not find site package node for site package name %s",siteName);
                    return null;
                }
            }
        }

        // Found the package for the site:
        if (sitePackageNode != null) {
            // Note: not using workspace since sites are all in master.
            NodeRef sitePackageSiteRef = (NodeRef) sitePackageNode.getPropertyAtTime( Acm.ACM_SITE_SITE, dateTime );
            if (sitePackageSiteRef != null && !sitePackageSiteRef.equals( initialSiteNode.getNodeRef() )) {
                log(Level.ERROR, HttpServletResponse.SC_NOT_FOUND, "Mismatch between site/package for site package name %s",siteName);
                return null;
            }

            // Get the project site by tracing up the parents until the parent is null:
            NodeRef siteParentRef = (NodeRef) initialSiteNode.getPropertyAtTime( Acm.ACM_SITE_PARENT, dateTime );
            EmsScriptNode siteParent = siteParentRef != null ? new EmsScriptNode(siteParentRef, services, response) : null;
            EmsScriptNode oldSiteParent = null;

            while (siteParent != null) {
                oldSiteParent = siteParent;
                siteParentRef = (NodeRef) siteParent.getPropertyAtTime( Acm.ACM_SITE_PARENT, dateTime );
                siteParent = siteParentRef != null ? new EmsScriptNode(siteParentRef, services, response) : null;
            }

            if (oldSiteParent != null && oldSiteParent.exists()) {
                siteNode = oldSiteParent;
            }
            else {
                log(Level.ERROR, HttpServletResponse.SC_NOT_FOUND, "Could not find parent project site for site package name %s", siteName);
                return null;
            }

        }
        // Otherwise, assume it is a project site:
        else {
            siteNode = initialSiteNode;
        }

        return new Pair<EmsScriptNode,EmsScriptNode>(sitePackageNode, siteNode);
    }

    /**
     * Return the matching alfresco site for the Package, or null
     *
     * @param pkgNode
     * @param workspace
     * @return
     */
    public EmsScriptNode getSiteForPkgSite(EmsScriptNode pkgNode, Date dateTime, WorkspaceNode workspace) {

        // Note: skipping the noderef check b/c our node searches return the noderefs that correspond
        //       to the nodes in the surf-config folder.  Also, we dont need the check b/c site nodes
        //       are always in the master workspace.
        NodeRef pkgSiteParentRef = (NodeRef)pkgNode.getPropertyAtTime( Acm.ACM_SITE_SITE, dateTime );
        EmsScriptNode pkgSiteParentNode = null;

        if (pkgSiteParentRef != null) {
            pkgSiteParentNode = new EmsScriptNode(pkgSiteParentRef, services);
        }
        // Couldn't find it using the property, try searching for it:
        else {
            // Search for it. Will have a cm:name = "site_"+sysmlid of site package node:
            String sysmlid = pkgNode.getSysmlId();
            if (sysmlid != null) {
                pkgSiteParentNode = findScriptNodeById(NodeUtil.sitePkgPrefix+sysmlid, workspace, dateTime, false);
            }
            else {
            	//TODO NOTE: Not Sure if to invoke pkgNode.toString() or pkgNode.getName() below:
                log(Level.WARN, "Parent package site does not have a sysmlid.  Node %s",pkgNode.toString());
            }
        }

        return pkgSiteParentNode;
    }

    /**
     * Returns the parent site of node, or the project site, or null.  The parent site of
     * the node is the alfresco site for the site package.
     *
     * @param node
     * @param siteNode
     * @param projectNode
     * @param workspace
     * @return
     */
    public EmsScriptNode findParentPkgSite(EmsScriptNode node,
                                           WorkspaceNode workspace,
                                           Date dateTime) {
        String runAsUser = AuthenticationUtil.getRunAsUser();
        boolean changeUser = !EmsScriptNode.ADMIN_USER_NAME.equals( runAsUser );
        if ( changeUser ) {
            AuthenticationUtil.setRunAsUser( EmsScriptNode.ADMIN_USER_NAME );
        }
        EmsScriptNode n = findParentPkgSiteImpl( node, workspace, dateTime );
        if ( changeUser ) {
            AuthenticationUtil.setRunAsUser( runAsUser );
        }
        return n;
    }
    public EmsScriptNode findParentPkgSiteImpl(EmsScriptNode node,
                                               WorkspaceNode workspace,
                                               Date dateTime) {

        EmsScriptNode pkgSiteParentNode = null;
        // Note: must walk up using the getOwningParent() b/c getParent() does not work
        //       for versioned nodes.  Note, that getOwningParent() will be null for
        //       the Project node, but we don't need to go farther up than this anyways
        EmsScriptNode siteParentReifNode = node.getOwningParent(dateTime, workspace, false, true);
        EmsScriptNode siteParent;
        while (siteParentReifNode != null && siteParentReifNode.exists()) {

            siteParent = siteParentReifNode.getReifiedPkg(dateTime, workspace);

            // If the parent is a package and a site, then its the parent site node:
            if (siteParentReifNode.hasAspect(Acm.ACM_PACKAGE) ) {
                Boolean isSiteParent = (Boolean) siteParentReifNode.getProperty( Acm.ACM_IS_SITE );
                if (isSiteParent != null && isSiteParent) {

                    // Get the alfresco Site for the site package node:
                    pkgSiteParentNode = getSiteForPkgSite(siteParentReifNode, dateTime, workspace);
                    break;
                }
            }

            // If the parent is the project, then the site will be the project Site:
            // Note: that projects are never nested so we just need to check if it is of project type
            String siteParentType = siteParent != null ? siteParent.getTypeShort() : null;
            String siteParentReifType = siteParentReifNode.getTypeShort();
            if (Acm.ACM_PROJECT.equals( siteParentType ) || Acm.ACM_PROJECT.equals( siteParentReifType )) {
                pkgSiteParentNode = siteParentReifNode.getSiteNode(dateTime, workspace);
                break;  // break no matter what b/c we have reached the project node
            }

            // siteParent could be null because the reified relationships may not have been
            // created properly (for old models)
            if (siteParent == null || siteParent.isWorkspaceTop()) {
                break;
            }

            siteParentReifNode = siteParentReifNode.getOwningParent(dateTime, workspace, false, true);
        }

        return pkgSiteParentNode;
    }

    
    /**
     * This needs to be called with the incoming JSON request to populate the local source
     * variable that is used in the sendDeltas call.
     * @param postJson
     * @throws JSONException
     */
    protected void populateSourceFromJson(JSONObject postJson) throws JSONException {
        if (postJson.has( "source" )) {
            source = postJson.getString( "source" );
        } else {
            source = null;
        }
    }
    
    /**
     * Send progress messages to the log, JMS, and email.
     * 
     * @param msg  The message
     * @param projectSysmlId  The project sysml id
     * @param workspaceName  The workspace name
     * @param sendEmail Set to true to send a email also
     */
    public void sendProgress( String msg, String projectSysmlId, String workspaceName,
                              boolean sendEmail) {
        
        String projectId = Utils.isNullOrEmpty(projectSysmlId) ? "unknown_project" : projectSysmlId;
        String workspaceId = Utils.isNullOrEmpty(workspaceName) ? "unknown_workspace" : workspaceName;        
        String subject = "Progress for project: "+projectId+" workspace: "+workspaceId;

        // Log the progress:
        log(Level.INFO,"%s msg: %s\n",subject,msg);
        //logger.info(subject+" msg: "+msg+"\n");
        
        // Send the progress over JMS:
        CommitUtil.sendProgress(msg, workspaceId, projectId);
        
        // Email the progress (this takes a long time, so only do it for critical events):
        if (sendEmail) {
            String hostname = NodeUtil.getHostname();
            if (!Utils.isNullOrEmpty( hostname )) {
                String sender = hostname + "@jpl.nasa.gov";
                String username = NodeUtil.getUserName();
                if (!Utils.isNullOrEmpty( username )) {
                    EmsScriptNode user = new EmsScriptNode(services.getPersonService().getPerson(username), 
                                                           services);
                    if (user != null) {
                        String recipient = (String) user.getProperty("cm:email");
                        if (!Utils.isNullOrEmpty( recipient )) {
                            ActionUtil.sendEmailTo( sender, recipient, msg, subject, services );
                        }
                    }
                }
            }
        }
        
    }
    
    /**
     * Creates a json like object in a string and puts the response in the message key
     * 
     * @return The resulting string, ie "{'message':response}" or "{}"
     */
    public String createResponseJson() {
        String resToString = response.toString();
        String resStr = !Utils.isNullOrEmpty( resToString ) ? resToString.replaceAll( "\n", "" ) : "";
        return !Utils.isNullOrEmpty( resStr ) ? String.format("{\"message\":\"%s\"}", resStr) : "{}";
    }

    public EmsSystemModel getSystemModel() {
        if ( systemModel == null ) {
            systemModel = new EmsSystemModel(this.services);
        }
        return systemModel;
    }

    public static EmsSystemModel globalSystemModel = null;
    public static EmsSystemModel getGlobalSystemModel() {
        if ( globalSystemModel == null ) {
            globalSystemModel = new EmsSystemModel(NodeUtil.getServices() );
        }
        return globalSystemModel;
    }

    public SystemModelToAeExpression< EmsScriptNode, EmsScriptNode, String, Object, EmsSystemModel > getSystemModelAe() {
        if ( sysmlToAe == null ) {
            setSystemModelAe();
        }
        return sysmlToAe;
    }

    public static SystemModelToAeExpression< EmsScriptNode, EmsScriptNode, String, Object, EmsSystemModel > getGlobalSystemModelAe() {
        if ( globalSysmlToAe == null ) {
            setGlobalSystemModelAe();
        }
        return globalSysmlToAe;
    }

    public void setSystemModelAe() {
        sysmlToAe =
                new SystemModelToAeExpression< EmsScriptNode, EmsScriptNode, String, Object, EmsSystemModel >( getSystemModel() );
    
    }

    public static void setGlobalSystemModelAe() {
        globalSysmlToAe =
                new SystemModelToAeExpression< EmsScriptNode, EmsScriptNode, String, Object, EmsSystemModel >( getGlobalSystemModel() );    
    }

    /**
     * Creates a ConstraintExpression for the passed constraint node and adds to the passed constraints
     *
     * @param constraintNode The node to parse and create a ConstraintExpression for
     * @param constraints The list of Constraints to add for tje node
     */
    public static void addConstraintExpression(EmsScriptNode constraintNode, Map< EmsScriptNode, Collection< Constraint >> constraints, WorkspaceNode ws) {
    
        if (constraintNode == null || constraints == null) return;
    
        EmsScriptNode exprNode = getConstraintExpression(constraintNode, ws);
    
        if (exprNode != null) {
            Expression<Boolean> expression = toAeExpression( exprNode );
    
            if (expression != null) {
    
                Collection< Constraint > constrs = constraints.get( constraintNode );
                if ( constrs == null ) {
                    constrs = new ArrayList< Constraint >();
                    constraints.put( constraintNode, constrs );
                }
                constrs.add(new ConstraintExpression( expression ));
            }
        }
    }

    public static <T> Expression<T> toAeExpression( EmsScriptNode exprNode ) {
        if ( exprNode == null ) {
            logger.warn( "called toAeExpression() with null argument" );
            return null;
        }
        Expression<Call> expressionCall = getGlobalSystemModelAe().toAeExpression( exprNode );
        if ( expressionCall == null ) {
            logger.warn( "toAeExpression("+exprNode+") returned null" );
            return null;
        }
        Call call = (Call) expressionCall.expression;
        if ( call == null ) {
            logger.warn( "toAeExpression("+exprNode+"): call is null, " + expressionCall );
            return null;
        }
        Expression< T > expression = null;
        try {
            expression = new Expression<T>(call.evaluate(true, false));
            
        // TODO -- figure out why eclipse gives compile errors for
        // including the exceptions while mvn gives errors for not
        // including them.
        } catch ( IllegalAccessException e ) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        } catch ( InvocationTargetException e ) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        } catch ( InstantiationException e ) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
        }
        return expression;
    }

    public static Map< EmsScriptNode, Collection< Constraint > > getAeConstraints( Set< EmsScriptNode > elements, WorkspaceNode ws ) {
        //Map<EmsScriptNode, Constraint> constraints = new LinkedHashMap<EmsScriptNode, Constraint>();
        Map< EmsScriptNode, Collection< Constraint > >  constraints = 
                new LinkedHashMap< EmsScriptNode, Collection< Constraint > >();
    
        // Search for all constraints in the database:
        Collection<EmsScriptNode> constraintNodes = getGlobalSystemModel().getType(null, Acm.ACM_CONSTRAINT);
//log(Level.INFO, "all constraints in database: " + constraintNodes);

        if (!Utils.isNullOrEmpty(constraintNodes)) {
    
            // Loop through each found constraint and check if it contains any of the elements
            // to be posted:
            for (EmsScriptNode constraintNode : constraintNodes) {
    
                // Parse the constraint node for all of the cm:names of the nodes in its expression tree:
                Set<String> constrElemNames = getConstraintElementNames(constraintNode, ws);
    
                // Check if any of the posted elements are in the constraint expression tree, and add
                // constraint if they are:
                // Note: if a Constraint element is in elements then it will also get added here b/c it
                //          will be in the database already via createOrUpdateMode()
                for (EmsScriptNode element : elements) {
    
                    String name = element.getName();
//log(Level.INFO, "element (" + element + ") vs. constraint (" + constraintNode + ")");
                    if ( element.equals( constraintNode ) || ( name != null && constrElemNames.contains(name) ) ) {
                        addConstraintExpression(constraintNode, constraints, ws);
                        break;
                    }
    
                } // Ends loop through elements
    
            } // Ends loop through constraintNodes
    
        } // Ends if there was constraint nodes found in the database

        // TODO -- This is temporarily commented out until code is added to tie
        // the constraints back to the nodes such as a constraint that a
        // property be grounded.
//        // Add all of the Parameter constraints:
//        ClassData cd = getGlobalSystemModelAe().getClassData();
//        // Loop through all the listeners:
//        for (ParameterListenerImpl listener : cd.getAeClasses().values()) {
//            // TODO: REVIEW
//            //       Can we get duplicate ParameterListeners in the aeClassses map?
//            constraints.addAll( listener.getConstraints( true, null ) );
//        }
    
        return constraints;
    }

    public static Map< EmsScriptNode, Expression<?> > getAeExpressions( Collection< EmsScriptNode > elements ) {
        Map<EmsScriptNode, Expression<?>> expressions = new LinkedHashMap< EmsScriptNode, Expression<?> >();
        for ( EmsScriptNode node : elements ) {
            // FIXME -- Don't we need to pass in a date and workspace?
            if ( node.hasAspect( Acm.ACM_EXPRESSION ) ) {
                Expression<?> expression = toAeExpression( node );
                if ( expression != null ) {
                    expressions.put( node, expression );
                }
            } else if ( node.hasValueSpecProperty( null, node.getWorkspace() ) ) {
                ArrayList< NodeRef > values = node.getValueSpecOwnedChildren( false, null, node.getWorkspace() );
                List< EmsScriptNode > valueElements = new ArrayList< EmsScriptNode >();
                for ( NodeRef value : values ) {
                    EmsScriptNode n = new EmsScriptNode( value, NodeUtil.getServices() );
                    if ( n.hasOrInheritsAspect( Acm.ACM_EXPRESSION ) ) {
                        valueElements.add( n );
                    }
                }
//                List< EmsScriptNode > valueElements =
//                        EmsScriptNode.toEmsScriptNodeList( values,
//                                                           NodeUtil.getServices(),
//                                                           null, null );
                Map< EmsScriptNode, Expression< ? > > ownedExpressions =
                       getAeExpressions( valueElements );
                if ( ownedExpressions != null && ownedExpressions.size() == 1 ) {
                    expressions.put( node, ownedExpressions.entrySet().iterator().next().getValue() );
                } else if ( ownedExpressions != null ) {
                    // TODO -- REVIEW -- is wrapping a collection of Expressions
                    // in an Expression goign to evaluate properly?
                    expressions.put( node, new Expression( ownedExpressions ) );
//                    for ( Expression< ? > ownedExpr : ownedExpressions.values() ) {
//                    }
                }
            }
        }
        return expressions;
    }

    public static Map<Object, Object> evaluate( Set< EmsScriptNode > elements, WorkspaceNode ws ) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        log(Level.INFO, "Will attempt to evaluate expressions where found!");
        Map< Object, Object > results = new LinkedHashMap< Object, Object >();

        Map< EmsScriptNode, Expression<?> > expressions = getAeExpressions( elements );
//log(Level.INFO, "expressions: " + expressions);
        if ( elements.size() != expressions.size() &&
                ( elements.size() != 1 || !elements.iterator().next().hasAspect( Acm.ACM_EXPRESSION ) ) ) {
        Map< EmsScriptNode, Collection< Constraint > > constraints = getAeConstraints( elements, ws );
//log(Level.INFO, "constraints: " + constraints);
    
        if ( !Utils.isNullOrEmpty( constraints ) ) {
            for ( Entry< EmsScriptNode, Collection< Constraint > > e : constraints.entrySet() ) {
                EmsScriptNode constraintNode = null;
                if ( e.getKey().hasOrInheritsAspect( Acm.ACM_CONSTRAINT ) ) {
                    constraintNode = e.getKey();
                }
                Collection< Constraint > constraintCollection = e.getValue();
                for ( Constraint c : constraintCollection ) {
                    if ( c != null ) {
                        if ( constraintNode != null && constraintCollection.size() == 1 ) {
                            results.put( constraintNode, c.isSatisfied( true, null ) );
                        } else {
                            results.put( c, c.isSatisfied( true, null ) );
                        }
                    }
                }
            }
        }
        }
        if ( !Utils.isNullOrEmpty( expressions ) ) {
            for ( Entry< EmsScriptNode, Expression<?> > e : expressions.entrySet() ) {
                if ( e != null && e.getKey() != null && e.getValue() != null ) {
                    Object resultVal = e.getValue().evaluate( true );
                    results.put( e.getKey(), resultVal );
                }
            }
        }
        return results;
    }
    
    /**
     * Construct a JSONArray from a Collection without triggering an infinite loop.
     *
     * @param collection
     *            A Collection.
     */
    public static JSONArray makeJSONArray(Collection collection) {
        JSONArray a = new JSONArray();
        if (collection != null) {
            Iterator iter = collection.iterator();
            while (iter.hasNext()) {
                a.put(jsonWrap(iter.next()));
            }
        }
        return a;
    }

    /**
     * Construct a JSONArray from an array
     *
     * @throws JSONException
     *             If not an array.
     */
    public static JSONArray makeJSONArray(Object array) throws JSONException {
        JSONArray a = new JSONArray();
        if (array.getClass().isArray()) {
            int length = Array.getLength(array);
            for (int i = 0; i < length; i += 1) {
                a.put(jsonWrap(Array.get(array, i)));
            }
        } else {
            throw new JSONException(
                    "JSONArray initial value should be a string or collection or array.");
        }
        return a;
    }
    
    /**
     * Fixes bug in JSONObject.wrap() where putting an unexpected object in a
     * JSONObject results in an infinite loop. The fix is to convert the object
     * to a string if it's type is not one of the usual suspects.
     * 
     * @param object
     * @return an Object that can be inserted as a value into a JSONObject 
     */
    public static Object jsonWrap( Object object ) {
        try {
            if (object == null) {
                return JSONObject.NULL;
            }
            if (object instanceof JSONObject || object instanceof JSONArray
                    || JSONObject.NULL.equals(object) || object instanceof JSONString
                    || object instanceof Byte || object instanceof Character
                    || object instanceof Short || object instanceof Integer
                    || object instanceof Long || object instanceof Boolean
                    || object instanceof Float || object instanceof Double
                    || object instanceof String) {
                return object;
            }

            if (object instanceof Collection) {
                return makeJSONArray((Collection) object);
            }
            if (object.getClass().isArray()) {
                return makeJSONArray(object);
            }
            if (object instanceof Map) {
                return makeJSONObject((Map) object);
            }
            return "" + object;
        } catch (Exception exception) {
            return null;
        }
    }
    
    /**
     * Construct a JSONObject from a Map without triggering an infinite loop.
     *
     * @param map
     *            A map object that can be used to initialize the contents of
     *            the JSONObject.
     * @throws JSONException
     */
    public static JSONObject makeJSONObject(Map map) {
        JSONObject jo = new JSONObject();
        if (map != null) {
            Iterator i = map.entrySet().iterator();
            while (i.hasNext()) {
                Map.Entry e = (Map.Entry) i.next();
                Object value = e.getValue();
                if (value != null) {
                    jo.put("" + e.getKey(), jsonWrap(value));
                }
            }
        }
        return jo;
    }

    public void evaluate( final Map<EmsScriptNode, JSONObject> elementsJsonMap,//Set< EmsScriptNode > elements, final JSONArray elementsJson,
                          JSONObject top, WorkspaceNode ws ) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        //final JSONArray elementsJson = new JSONArray();
        Set< EmsScriptNode > elements = elementsJsonMap.keySet();
        Map< Object, Object > results = evaluate( elements, ws );
        for ( EmsScriptNode element : elements ) {
            JSONObject json = elementsJsonMap.get( element );//element.toJSONObject(ws, null);
            Object result = results.get( element );
            if ( result != null ) {
                try {
                    json.putOpt( "evaluationResult", jsonWrap( result ) );
                    results.remove( element );
                } catch ( Throwable e ) {
                    log( Level.WARN, "Evaluation failed for %s", element );
                }
            }
            //elementsJson.put( json );
        }
        // Put constraint evaluation results in json.
        JSONArray resultJarr = new JSONArray();
        for ( Object k : results.keySet() ) {
            JSONObject r = new JSONObject();
            r.put( "expression", k.toString() );
            Object v = results.get( k );
            r.put( "value", "" + v );
            resultJarr.put( r );
        }
        
        //top.put( "elements", elementsJson );
        if ( resultJarr.length() > 0 ) top.put( "evaluations", resultJarr );

    }
    
    public void fix( Set< EmsScriptNode > elements, WorkspaceNode ws ) {
    
        log(Level.INFO, "Will attempt to fix constraint violations if found!");
    
        SystemModelSolver< EmsScriptNode, EmsScriptNode, EmsScriptNode, EmsScriptNode, String, String, Object, EmsScriptNode, String, String, EmsScriptNode >  solver =
                new SystemModelSolver< EmsScriptNode, EmsScriptNode, EmsScriptNode, EmsScriptNode, String, String, Object, EmsScriptNode, String, String, EmsScriptNode >(getSystemModel(), new ConstraintLoopSolver() );
    
        Map< EmsScriptNode, Collection< Constraint > > constraintMap = getAeConstraints( elements, ws );
        ArrayList< Constraint > constraints = new ArrayList< Constraint >();
        for ( Collection< Constraint > coll : constraintMap.values() ) {
            constraints.addAll( coll );
        }
    
        // Solve the constraints:
        if (!Utils.isNullOrEmpty( constraints )) {
    
            Random.reset();
    
            // Solve!!!!
            boolean result = false;
            try {
                //Debug.turnOn();
                result = solver.solve(constraints);
    
            } finally {
                //Debug.turnOff();
            }
            if (!result) {
                log( Level.ERROR, "Was not able to satisfy all of the constraints!" );
            }
            else {
                log( Level.INFO, "Satisfied all of the constraints!" );
    
                // Update the values of the nodes after solving the constraints:
                EmsScriptNode node;
                Parameter<Object> param;
                Map< EmsScriptNode, Parameter< Object > > params = getGlobalSystemModelAe().getExprParamMap();
                if ( Utils.isNullOrEmpty( params ) ) {
                    log( Level.ERROR, "Solver had no parameters in map to assign the solution!" );
                } else {
                    Set<Entry<EmsScriptNode, Parameter<Object>>> entrySet = params.entrySet();
                    for (Entry<EmsScriptNode, Parameter<Object>> entry : entrySet) {
                        node = entry.getKey();
                        param = entry.getValue();
                        systemModel.setValue(node, (Serializable)param.getValue());
                    }
        
                    log( Level.INFO, "Updated all node values to satisfy the constraints!" );
                }
            }
        } // End if constraints list is non-empty
    
    }

    /**
     * Parses the Property and returns a set of all the node names
     * in the property.
     *
     * @param propertyNode The node to parse
     * @return Set of cm:name
     */
    protected static Set<String> getPropertyElementNames(EmsScriptNode propertyNode) {
    
        Set<String> names = new HashSet<String>();
    
        if (propertyNode != null) {
    
            String name = propertyNode.getName();
    
            if (name != null) names.add(name);
    
            // See if it has a value property:
            Collection< EmsScriptNode > propertyValues =
                    getGlobalSystemModel().getProperty(propertyNode, Acm.JSON_VALUE);
    
            if (!Utils.isNullOrEmpty(propertyValues)) {
                  for (EmsScriptNode value : propertyValues) {
    
                      names.add(value.getName());
    
                      // TODO REVIEW
                      //      need to be able to handle all ValueSpecification types?
                      //      some of them have properties that point to nodes, so
                      //      would need to process them also
                  }
            }
        }
    
        return names;
    }

    /**
     * Parses the Parameter and returns a set of all the node names
     * in the parameter.
     *
     * @param paramNode The node to parse
     * @return Set of cm:name
     */
    protected static Set<String> getParameterElementNames(EmsScriptNode paramNode) {
    
        Set<String> names = new HashSet<String>();
    
        if (paramNode != null) {
    
            String name = paramNode.getName();
    
            if (name != null) names.add(name);
    
            // See if it has a defaultParamaterValue property:
            Collection< EmsScriptNode > paramValues =
                    getGlobalSystemModel().getProperty(paramNode, Acm.JSON_PARAMETER_DEFAULT_VALUE);
    
            if (!Utils.isNullOrEmpty(paramValues)) {
                  names.add(paramValues.iterator().next().getName());
            }
        }
    
        return names;
    }

    /**
     * Parses the Operation and returns a set of all the node names
     * in the operation.
     *
     * @param opNode The node to parse
     * @return Set of cm:name
     */
    protected static Set<String> getOperationElementNames(EmsScriptNode opNode) {
    
        Set<String> names = new HashSet<String>();
    
        if (opNode != null) {
    
            String name = opNode.getName();
    
            if (name != null) names.add(name);
    
            // See if it has a operationParameter and/or operationExpression property:
            Collection< EmsScriptNode > opParamNodes =
                    getGlobalSystemModel().getProperty(opNode, Acm.JSON_OPERATION_PARAMETER);
    
            if (!Utils.isNullOrEmpty(opParamNodes)) {
              for (EmsScriptNode opParamNode : opParamNodes) {
                  names.addAll(getParameterElementNames(opParamNode));
              }
            }
    
            Collection< EmsScriptNode > opExprNodes =
                    getGlobalSystemModel().getProperty(opNode, Acm.JSON_OPERATION_EXPRESSION);
    
            if (!Utils.isNullOrEmpty(opExprNodes)) {
                names.add(opExprNodes.iterator().next().getName());
            }
        }
    
        return names;
    }

    /**
     * Parses the expression and returns a set of all the node names
     * in the expression.
     *
     * @param expressionNode The node to parse
     * @param ws 
     * @param date 
     * @return Set of cm:name
     */
    protected static Set<String> getExpressionElementNames(EmsScriptNode expressionNode, Date date , WorkspaceNode ws ) {
    
        Set<String> names = new HashSet<String>();
    
        if (expressionNode != null) {
    
            // Add the name of the Expression itself:
            String name = expressionNode.getName();
    
            if (name != null) names.add(name);
    
            // FIXME -- need to give date/workspace context 
            // Process all of the operand properties:
            Collection< EmsScriptNode > properties =
                    getGlobalSystemModel().getProperty( expressionNode, Acm.JSON_OPERAND);
    
            if (!Utils.isNullOrEmpty(properties)) {
    
              EmsScriptNode valueOfElementNode = null;
    
              for (EmsScriptNode operandProp : properties) {
    
                if (operandProp != null) {
    
                    names.add(operandProp.getName());
    
                    // FIXME -- need to give date/workspace context 
                    // Get the valueOfElementProperty node:
                    Collection< EmsScriptNode > valueOfElemNodes =
                            getGlobalSystemModel().getProperty(operandProp, Acm.JSON_ELEMENT_VALUE_ELEMENT);
    
                    // If it is a elementValue, then this will be non-empty:
                    if (!Utils.isNullOrEmpty(valueOfElemNodes)) {
    
                      // valueOfElemNodes should always be size 1 b/c elementValueOfElement
                      // is a single NodeRef
                      valueOfElementNode = valueOfElemNodes.iterator().next();
                    }
    
                    // Otherwise just use the node itself as we are not dealing with
                    // elementValue types:
                    else {
                      valueOfElementNode = operandProp;
                    }
    
                    if (valueOfElementNode != null) {
    
                      // FIXME -- need to give date/workspace context 
                      String typeString = getGlobalSystemModel().getTypeString(valueOfElementNode, null);
    
                      // FIXME -- need to give date/workspace context 
                      // If it is a Operation then see if it then process it:
                      if (typeString.equals(Acm.JSON_OPERATION)) {
                          names.addAll(getOperationElementNames(valueOfElementNode));
                      }
    
                      // If it is a Expression then process it recursively:
                      else if (typeString.equals(Acm.JSON_EXPRESSION)) {
                          names.addAll(getExpressionElementNames(valueOfElementNode, date, ws));
                      }
    
                      // FIXME -- need to give date/workspace context 
                      // If it is a Parameter then process it:
                      else if (typeString.equals(Acm.JSON_PARAMETER)) {
                          names.addAll(getParameterElementNames(valueOfElementNode));
                      }
    
                      // FIXME -- need to give date/workspace context 
                      // If it is a Property then process it:
                      else if (typeString.equals(Acm.JSON_PROPERTY)) {
                          names.addAll(getPropertyElementNames(valueOfElementNode));
                      }
    
                    } // ends if valueOfElementNode != null
    
                } // ends if operandProp != null
    
              } // ends for loop through operand properties
    
            } // ends if operand properties not null or empty
    
        } // ends if expressionNode != null
    
        return names;
    }

    /**
     * Parses the expression for the passed constraint, and returns a set of all the node
     * names in the expression.
     *
     * @param constraintNode The node to parse
     * @param ws 
     * @return Set of cm:name
     */
    protected static Set<String> getConstraintElementNames(EmsScriptNode constraintNode, WorkspaceNode ws ) {
    
        Set<String> names = new LinkedHashSet<String>();
    
        if (constraintNode != null) {
    
            // Add the name of the Constraint:
            String name = constraintNode.getName();
    
            if (name != null) names.add(name);
    
            // Get the Expression for the Constraint:
            EmsScriptNode exprNode = getConstraintExpression(constraintNode, ws);
    
            // Add the names of all nodes in the Expression:
            if (exprNode != null) {
    
                // Get elements names from the Expression:
                names.addAll(getExpressionElementNames(exprNode, null, ws));
    
                // REVIEW: Not using the child associations b/c
                // ElementValue's elementValueOfElement has a different
                // owner, and wont work for our demo either b/c
                // not everything is under one parent
            }
    
        }
    
        return names;
    }

    /**
     * Parse out the expression from the passed constraint node
     *
     * @param constraintNode The node to parse
     * @return The Expression node for the constraint
     */
    private static EmsScriptNode getConstraintExpression(EmsScriptNode constraintNode, WorkspaceNode ws) {
    
        if (constraintNode == null) return null;
    
        // FIXME -- need to give date/workspace context 
        // Get the constraint expression:
        Collection<EmsScriptNode> expressions =
                getGlobalSystemModel().getProperty( constraintNode, Acm.JSON_CONSTRAINT_SPECIFICATION );
    
        // This should always be of size 1:
        return Utils.isNullOrEmpty( expressions ) ? null :  expressions.iterator().next();
    
    }
    
}
