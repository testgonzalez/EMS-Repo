package gov.nasa.jpl.view_repo.webscripts;

import gov.nasa.jpl.mbee.util.Timer;
import gov.nasa.jpl.mbee.util.Utils;
import gov.nasa.jpl.view_repo.util.CommitUtil;
import gov.nasa.jpl.view_repo.util.EmsScriptNode;
import gov.nasa.jpl.view_repo.util.EmsTransaction;
import gov.nasa.jpl.view_repo.util.NodeUtil;
import gov.nasa.jpl.view_repo.util.WorkspaceDiff;
import gov.nasa.jpl.view_repo.util.WorkspaceNode;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.*;
import org.json.JSONArray;
import org.alfresco.repo.model.Repository;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.security.PermissionService;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

public class MmsModelDelete extends AbstractJavaWebScript {
    static Logger logger = Logger.getLogger(MmsModelDelete.class);

    Set< EmsScriptNode > valueSpecs = new LinkedHashSet<EmsScriptNode>();

    @Override
    protected boolean validateRequest( WebScriptRequest req, Status status ) {
        // TODO Auto-generated method stub
        return false;
    }

    public MmsModelDelete() {
        super();
    }

    public MmsModelDelete(Repository repositoryHelper, ServiceRegistry registry) {
        super(repositoryHelper, registry);
    }

    /**
     * Entry point
     */
    @Override
    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {
        MmsModelDelete instance = new MmsModelDelete(repository, getServices());
        return instance.executeImplImpl(req,  status, cache, runWithoutTransactions);
    }

    @Override
    protected Map< String, Object > executeImplImpl( WebScriptRequest req,
                                                 Status status, Cache cache ) {
        if ( logger.isInfoEnabled() ) {
            String user = AuthenticationUtil.getFullyAuthenticatedUser();
            logger.info( user + " " + req.getURL() );
        }


        if ( logger.isInfoEnabled() ) {
            String user = AuthenticationUtil.getFullyAuthenticatedUser();
            logger.info( user + " " + req.getURL() );
        }
        
        Timer timer = new Timer();
        
        printHeader( req );

        Map<String, Object> model = new HashMap<String, Object>();

        JSONObject result = null;

        try {
            result = handleRequest( req );
            if (result != null) {
                if (!Utils.isNullOrEmpty(response.toString())) result.put("message", response.toString());
                model.put( "res", NodeUtil.jsonToString( result, 2 ) );
            }
        } catch (JSONException e) {
           log(Level.ERROR, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not create JSON\n");
           e.printStackTrace();
        } catch (Exception e) {

           if (e.getCause() instanceof JSONException) {
               log(Level.WARN, HttpServletResponse.SC_BAD_REQUEST,"Bad JSON body provided\n"); 
           } else {
               log(Level.ERROR,  HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error\n");
           }
           e.printStackTrace();
        }
        if (result == null) {
            model.put( "res", createResponseJson());
        }

        status.setCode(responseStatus.getCode());

        printFooter();

        if (logger.isInfoEnabled()) logger.info( "Deletion completed" );
        if ( logger.isInfoEnabled() ) {
            logger.info( String.format( "ModeDelete: %s", timer ) );
        }

        return model;
    }

    protected JSONObject handleRequest(WebScriptRequest req) throws JSONException {
        JSONObject result = null;

        Date start = new Date(); 
        String user = AuthenticationUtil.getRunAsUser();
        String wsId = null;
        WorkspaceNode workspace = getWorkspace( req, //true, // not creating ws!
                                                user );
        boolean wsFound = workspace != null;
        if ( wsFound ) {
            wsId = workspace.getSysmlId();
        } else {
            wsId = getWorkspaceId( req );
            if ( wsId != null && wsId.equalsIgnoreCase( "master" ) ) {
                wsFound = true;
            }
        }
        if ( !wsFound ) {
            log( Level.ERROR,
                 Utils.isNullOrEmpty( wsId ) ? HttpServletResponse.SC_BAD_REQUEST
                                             : HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                 "Could not find or create %s workspace.\n",wsId);
            return result;
        }
        setWsDiff(workspace);   // need to initialize the workspace diff

        String projectId = null;

        // parse based off of URL or content body
        String elementId = req.getServiceMatch().getTemplateVars().get("elementId");
        List<String> ids = new ArrayList<String>();
        if (null != elementId) {
            ids.add( elementId );
        } else {
            JSONObject requestJson = (JSONObject) req.parseContent();
            if (requestJson != null) {
                populateSourceFromJson( requestJson );
                if (requestJson.has("elements")) {
                    JSONArray elementsJson = requestJson.getJSONArray( "elements" );
                    if (elementsJson != null) {
                        for (int ii = 0; ii < elementsJson.length(); ii++) {
                            String id = elementsJson.getJSONObject( ii ).getString( "sysmlid" );
                            ids.add(id);
                        }
                    }
                }
            }
        }
        
        if (ids.size() <= 0) {
            log(Level.WARN, HttpServletResponse.SC_BAD_REQUEST, "no elements specified for deletion");
        } else {
            try {
                projectId = deleteNodes(ids, workspace);
                Date end = new Date();
        
                boolean showAll = false;
                result = wsDiff.toJSONObject( start, end, showAll );
        
                if (wsDiff.isDiff()) {
                    // Send deltas to all listeners
                    if ( !CommitUtil.sendDeltas(result, wsId, projectId, source) ) {
                        //log(Level.WARN, "createOrUpdateModel deltas not posted properly");
                        logger.warn("deltas not posted properly");
                    }
        
                    String msg = "model delete";
                    CommitUtil.commit( workspace, result, msg, runWithoutTransactions, services, response );
                }                

                // apply aspects after wsDiff JSON has been created since the wsDiff 
                // toJSONObject skips deleted objects
                applyAspects();
                
            } catch (Exception e) {
                // do nothing, just a 404 not found
            }
        }

        return result;
    }
    
    protected void applyAspects() {
        
        Set<EmsScriptNode> nodesToDelete = new HashSet<EmsScriptNode>();
        nodesToDelete.addAll( wsDiff.getDeletedElements().values() );
        nodesToDelete.addAll( valueSpecs );
        for (final EmsScriptNode deletedNode: nodesToDelete) {
            
            if (deletedNode.exists()) {
                
                boolean noTransaction = false;
                new EmsTransaction(getServices(), getResponse(), getResponseStatus(), noTransaction) {
                    
                    @Override
                    public void run() throws Exception {

                        deletedNode.removeAspect( "ems:Added" );
                        deletedNode.removeAspect( "ems:Updated" );
                        deletedNode.removeAspect( "ems:Moved" );
                        deletedNode.createOrUpdateAspect( "ems:Deleted" );
                        
                    }
                };
            }
        }
    }


    protected String deleteNodes(List<String> ids, WorkspaceNode workspace) throws Exception {
        String projectId = null;
        
        List<EmsScriptNode> nodes = new ArrayList<EmsScriptNode>();

        for (String id: ids) {
            // Searching for deleted nodes also, in case they try to delete a element that has
            // already been deleted in the current workspace.
            EmsScriptNode node = findScriptNodeById(id, workspace, null, true);
            if (node != null && node.exists()) {
                // remove any site characterization information
                ModelPost.handleSiteRemoval( node, workspace, services );
                
                String tmpProjectId = deleteNodeRecursively(node, workspace);
                if ( null != tmpProjectId && null == projectId ) {
                    projectId = tmpProjectId;
                }
                nodes.add( node );
            } else {
                if (ids.size() == 1) {
                    String workspaceName = "master";
                    String msg = "Node already deleted.";
                    if (node == null) msg = "Node does not exist.";
                    if (workspace != null) workspaceName = workspace.getSysmlName();
                    log( Level.ERROR, 
                         String.format( "Could not find node %s in workspace %s. %s",
                                        id, workspaceName, msg), 
                        HttpServletResponse.SC_NOT_FOUND);
                    throw new Exception();
                }
            }
        }
        
        return projectId;
    }
    
    protected String deleteNodeRecursively(EmsScriptNode root, WorkspaceNode workspace) {
        String projectId = null;

        try {
            handleElementHierarchy( root, workspace, true );

            Set< String> idsToRemove = new HashSet<String>();
            for (Entry< String, EmsScriptNode > entry: wsDiff.getDeletedElements().entrySet()) {
                EmsScriptNode node = entry.getValue();
                String id = entry.getKey();
                if ( node.isOwnedValueSpec(null, workspace) ) {
                    valueSpecs.add( node );
                    idsToRemove.add( id );
                }
            }
            
            // Remove value specs from elements, elementsVersions and deletedElements:
            for (String id : idsToRemove) {
                wsDiff.getDeletedElements().remove( id );
                wsDiff.getElementsVersions().remove( id );
                wsDiff.getElements().remove( id );
            }
            
        } catch (Throwable e) {
            try {
                if (e instanceof JSONException) {
                        log(Level.ERROR, HttpServletResponse.SC_BAD_REQUEST, "MmsModelDelete.handleRequest: JSON malformed: %s", e.getMessage());
                } else {
                        log(Level.ERROR, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "MmsModelDelete.handleRequest: DB transaction failed: %s", e.getMessage());
                }
                
//                trx.rollback();
//                NodeUtil.setInsideTransactionNow( false );
//                log(Level.ERROR, "\t####### ERROR: Needed to rollback: %s", e.getMessage());
                e.printStackTrace();
            } catch (Throwable ee) {
                log(Level.ERROR, "\tMmsModelDelete.handleRequest: rollback failed: %s", ee.getMessage());
                ee.printStackTrace();
                e.printStackTrace();
            }
        } finally {
            for (EmsScriptNode deletedNode: wsDiff.getDeletedElements().values()) {
                deletedNode.getOrSetCachedVersion();
            }
        }
        
        return projectId;

    }
    
    
    /**
     * Builds up the list of deleted elements
     * @param node
     * @param workspace
     */
    public void delete(EmsScriptNode node, final WorkspaceNode workspace,
                       WorkspaceDiff workspaceDiff) {
        if(workspaceDiff != null && wsDiff == null)
            wsDiff = workspaceDiff;

        if (checkPermissions(node, PermissionService.WRITE)) {
            if ( node == null || !node.exists() ) {
                log(Level.ERROR, "Trying to delete a non-existent node! %s", node.toString());
                return;
            }

            // Add the element to the specified workspace to be deleted from there.
            if ( workspace != null && workspace.exists() && node != null
                 && node.exists() && !node.isWorkspace() ) {
                EmsScriptNode newNodeToDelete = null;
                if ( !workspace.equals( node.getWorkspace() ) ) {
                    try {
                        final ArrayList<EmsScriptNode> list = new ArrayList< EmsScriptNode >();
                        final EmsScriptNode fNode = node;
                        new EmsTransaction( getServices(), getResponse(), getResponseStatus() ) {
                            @Override
                            public void run() throws Exception {
                                EmsScriptNode n = workspace.replicateWithParentFolders( fNode );
                                list.add(n);
                            }
                        };
                        if ( !list.isEmpty() ) newNodeToDelete = list.get( 0 );
                        node = newNodeToDelete;
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        if (newNodeToDelete != null) {
                            node = newNodeToDelete;
                        }
                    }
                }
            }

            if ( node != null && node.exists() ) {
                addToWsDiff( node, workspace );

                deleteRelationships(node, "sysml:relationshipsAsSource", "sysml:relAsSource", workspace);
                deleteRelationships(node, "sysml:relationshipsAsTarget", "sysml:relAsTarget", workspace);
            }
        }
    }

    /**
     * Deletes the relationships that are attached to this node
     * @param node          Node to delete relationships for
     * @param aspectName    String of the aspect name to look for
     * @param propertyName  String of the property to remove
     */
    private void deleteRelationships(EmsScriptNode node, String aspectName, String propertyName,
                                     WorkspaceNode workspace) {
        if (node.hasAspect( aspectName )) {
            ArrayList<NodeRef> relRefs = node.getPropertyNodeRefs( propertyName, null, workspace );
            for (NodeRef relRef: relRefs) {
                EmsScriptNode relNode = new EmsScriptNode(relRef, services, response);
                addToWsDiff( relNode, workspace );
            }
        }
    }

    /**
     * Build up the element hierarchy from the specified root
     * @param root      Root node to get children for
     * @param workspace
     * @throws JSONException
     */
    protected void handleElementHierarchy( final EmsScriptNode root,
                                           final WorkspaceNode workspace,
                                           final boolean recurse ) {
        if (root == null) {
            return;
        }

        boolean noRecurseTransaction = true;
        if (recurse) {
            
            new EmsTransaction(getServices(), getResponse(), getResponseStatus(), noRecurseTransaction) {
                
                @Override
                public void run() throws Exception {
                    for ( NodeRef childRef : root.getOwnedChildren(true, null, workspace) ) {
                            EmsScriptNode child = new EmsScriptNode(childRef, services, response);
                            handleElementHierarchy(child, workspace, recurse);
                    }
                }
                
            };
            
        }

        boolean noTransaction = false;
        new EmsTransaction(getServices(), getResponse(), getResponseStatus(), noTransaction) {
            
            @Override
            public void run() throws Exception {
                // Delete the node:
                if (root.exists()) {
                    delete(root, workspace, null);
                }
                
            }
        };
        
        // TODO: REVIEW may not need this b/c addToWsDiff() does not add in reified packages
        //       Also, code in ModelPost assumes we never delete reified packages
//        // Delete the reified pkg if it exists also:
//        EmsScriptNode pkgNode = findScriptNodeById(root.getSysmlId() + "_pkg",
//                                                   workspace, null, false);
//
//        if (pkgNode != null) {
//            delete(pkgNode, workspace, null);
//        }
    }

    /**
     * Add everything to the commit delete
     * @param node
     */
    private void addToWsDiff( final EmsScriptNode node, WorkspaceNode workspace ) {
        String sysmlId = node.getSysmlId();
        if (!sysmlId.endsWith( "_pkg" )) {
            if(wsDiff.getElementsVersions() != null)
                wsDiff.getElementsVersions().put( sysmlId, node.getHeadVersion() );
            if(wsDiff.getElements() != null)
                wsDiff.getElements().put( sysmlId, node );
            if(wsDiff.getDeletedElements() != null)
                wsDiff.getDeletedElements().put( sysmlId, node );
            
            // Remove from the ownedChildren of the owner:
            // Note: added this for when we are deleting embedded value specs that are no longer be used
            //
            // The parent returned may be from the versionStore://version2Store if the node that is being
            // deleted is on a copy time branch.  This is b/c correctForWorkspaceCopyTime() will return a versioned
            // node at the copyTime of the branch if the node it found was not in the current workspace.  This will
            // be the case if the parent was never modified in the current workspace, as clone() does not update
            // "owner", so even though we replicate what we are deleting in previous code, the "owner" of node will
            // still point to the parent branch.  Consequently, we will try to update a versioned node, but
            // makeSureNodeRefIsNotFrozen() will catch this, and hopefully correctly remedy the situation.  This
            // occurs with regression test DeleteDeleteDeleteWs1
            final EmsScriptNode parent = node.getOwningParent( null, workspace, false );
            if (parent != null && parent.exists()) {
                boolean noTransaction = false;
                new EmsTransaction(getServices(), getResponse(), getResponseStatus(), noTransaction) {
                    
                    @Override
                    public void run() throws Exception {
                        parent.removeFromPropertyNodeRefs("ems:ownedChildren", node.getNodeRef() );
                    }
                };
            }
        }
    }
}
