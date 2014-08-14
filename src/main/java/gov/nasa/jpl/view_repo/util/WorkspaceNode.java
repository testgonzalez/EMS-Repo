/**
 *
 */
package gov.nasa.jpl.view_repo.util;

import gov.nasa.jpl.mbee.util.Debug;
import gov.nasa.jpl.view_repo.util.NodeUtil.SearchType;
import gov.nasa.jpl.view_repo.webscripts.AbstractJavaWebScript;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.servlet.http.HttpServletResponse;

import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.springframework.extensions.webscripts.Status;

/**
 * WorkspaceNode is an EmsScriptNode and a folder containing changes to a parent
 * workspace.
 *
 */
public class WorkspaceNode extends EmsScriptNode {

    private static final long serialVersionUID = -7143644366531706115L;

    /**
     * @param nodeRef
     * @param services
     * @param response
     * @param status
     */
    public WorkspaceNode( NodeRef nodeRef, ServiceRegistry services,
                          StringBuffer response, Status status ) {
        super( nodeRef, services, response, status );
    }

    /**
     * @param nodeRef
     * @param services
     * @param response
     */
    public WorkspaceNode( NodeRef nodeRef, ServiceRegistry services,
                          StringBuffer response ) {
        super( nodeRef, services, response );
    }

    /**
     * @param nodeRef
     * @param services
     */
    public WorkspaceNode( NodeRef nodeRef, ServiceRegistry services ) {
        super( nodeRef, services );
    }

    @Override
    public WorkspaceNode getWorkspace() {
        log( "Warning! calling getWorkspace on a workspace! " + getName() );
        return this;
    }

    @Override
    public WorkspaceNode getParentWorkspace() {
        NodeRef ref = (NodeRef)getProperty("sysml:parent");
        if ( ref == null ) return null;
        WorkspaceNode parentWs = new WorkspaceNode( ref, getServices() );
        return parentWs;
    }

    @Override
    public void setWorkspace( WorkspaceNode workspace, NodeRef source ) {
        String msg = "Cannot set the workspace of a workspace!";
        if ( getResponse() != null ) {
            getResponse().append( msg + "\n" );
            if ( getStatus() != null ) {
                getStatus().setCode( HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                     msg );
            }
        }
        Debug.error( msg );
    }

    /**
     * Create a workspace folder within the specified folder or (if the folder
     * is null) within the specified user's home folder.
     *
     * @param sysmlId
     *            the name/identifier of the workspace
     * @param userName
     *            the name of the user that is creating the workspace
     * @param folder
     *            the folder within which to create the workspace
     * @param services
     * @param response
     * @param status
     * @return the new workspace or null if the workspace could not be created
     *         because both the containing folder and the user name were both
     *         unspecified (non-existent)
     */
    public static WorkspaceNode createWorskpaceInFolder( String sysmlId,
                                                         //String wsName,
                                                         String userName,
                                                         EmsScriptNode folder,
                                                         ServiceRegistry services,
                                                         StringBuffer response,
                                                         Status status ) {
        if ( sysmlId == null ) {
            sysmlId = NodeUtil.createId( services );
        }
        if ( folder == null || !folder.exists() ) {
            //String userName = ws.getOwner();
            if ( userName != null && userName.length() > 0 ) {
                folder = NodeUtil.getUserHomeFolder( userName );
                if ( Debug.isOn() ) Debug.outln( "user home folder: " + folder );
            }
        }
        if ( folder == null || !folder.exists() ) {
            Debug.error( true, false, "\n%%% Error! no folder, " + folder
                                      + ", within which to create workspace, "
                                      + sysmlId );
        }

        WorkspaceNode ws = new WorkspaceNode( folder.createFolder( sysmlId ).getNodeRef(),
                                              services, response, status );
        ws.addAspect( "ems:Workspace" );

        ws.setProperty( "ems:parent", folder );
        if ( folder.isWorkspace() ) {
            if ( Debug.isOn() ) Debug.outln( "folder is a workspace: " + folder );
            WorkspaceNode parentWorkspace =
                    new WorkspaceNode( folder.getNodeRef(), services, response,
                                       status );
            if ( Debug.isOn() ) Debug.outln( "parent workspace: " + parentWorkspace );
            parentWorkspace.appendToPropertyNodeRefs( "ems:children", ws.getNodeRef() );
        }
        ws.setProperty( "ems:lastTimeSyncParent", new Date() );
        if ( Debug.isOn() ) Debug.outln( "created workspace " + ws + " in folder " + folder );
        return ws;
    }

    public static WorkspaceNode createWorskpaceFromSource( String sysmlId,
    									String userName,
    									String sourceId,
    									EmsScriptNode folder,
    									ServiceRegistry services,
    									StringBuffer response,
    									Status status ) {
    	if ( sysmlId == null ) {
    		sysmlId = NodeUtil.createId( services );
    	}
    	if ( folder == null || !folder.exists() ) {
    		//String userName = ws.getOwner();
    		if ( userName != null && userName.length() > 0 ) {
    			folder = NodeUtil.getUserHomeFolder( userName );
    			if ( Debug.isOn() ) Debug.outln( "user home folder: " + folder );
    		}
    	}
    	if ( folder == null || !folder.exists() ) {
    		Debug.error( true, false, "\n%%% Error! no folder, " + folder
    				+ ", within which to create workspace, "
    				+ sysmlId );
    	}

    	WorkspaceNode ws = new WorkspaceNode( folder.createFolder( sysmlId ).getNodeRef(),
    			services, response, status );
    	ws.addAspect( "ems:Workspace" );
    	ws.addAspect( "ems:MergeSource" );
    	ws.setProperty( "ems:parent", folder );
    	WorkspaceNode parentWorkspace = AbstractJavaWebScript.getWorkspaceFromId(sourceId, services, response, status, false, userName);
    	if ( Debug.isOn() ) Debug.outln( "parent workspace: " + parentWorkspace );
    	if(parentWorkspace != null) {
    		parentWorkspace.appendToPropertyNodeRefs( "ems:children", ws.getNodeRef() );
    		ws.setProperty( "ems:mergeSource", parentWorkspace.getNodeRef());
    	}
    	ws.setProperty( "ems:lastTimeSyncParent", new Date() );
    	if ( Debug.isOn() ) Debug.outln( "created workspace " + ws + " in folder " + folder );
    	return ws;
    }

    // A workspace is not created inside the folder of another workspace, so
    // this method is commented out.
//    public WorkspaceNode createWorskpace( String sysmlId ) {
//        return createWorskpaceInFolder( sysmlId, this.getOwner(), this,
//                                        getServices(), getResponse(),
//                                        getStatus() );
//    }

    /**
     * Determine whether the given node is correct for this workspace, meaning
     * that it is either modified in this workspace or is contained by the
     * parent workspace and unmodified in this workspace.
     *
     * @param node
     * @return true iff the node is in this workspace
     */
    public boolean contains( EmsScriptNode node  ) {
        WorkspaceNode nodeWs = node.getWorkspace();
        if ( this.equals( nodeWs ) ) return true;
        WorkspaceNode parentWs = getParentWorkspace();
        if ( parentWs == null ) return ( nodeWs == null );
        return parentWs.contains( node );
    }

    /**
     * Replicate this node and its parent/grandparent folders into this
     * workspace if not already present.
     *
     * @param node
     * @return
     */
    public EmsScriptNode replicateWithParentFolders( EmsScriptNode node ) {
        if ( Debug.isOn() ) Debug.outln( "replicateFolderWithChain( " + node + " )" );
        if ( node == null ) return null;
        EmsScriptNode newFolder = node;

        String thisName = exists() ? getName() : null;
        String nodeName = node != null && node.exists() ? node.getName() : null;

        // make sure the folder's parent is replicated
        EmsScriptNode parent = node.getParent();

        if ( parent == null || parent.isWorkspaceTop() ) {
            parent = this;
//            if ( Debug.isOn() ) Debug.outln( "returning newFolder for workspace top: " + newFolder );
//            return newFolder;
        }
        String parentName = parent != null && parent.exists() ? parent.getName() : null;

        if ( parent != null && parent.exists() && !this.equals( parent.getWorkspace() ) ) {
            parent = findScriptNodeByName( parentName, this, null );
            if ( parent == null || !parent.exists() || !this.equals( parent.getWorkspace() ) ) {
                parent = replicateWithParentFolders( parent );
            }
            //if ( Debug.isOn() ) Debug.outln( "moving newFolder " + newFolder + " to parent " + parent );
            //newFolder.move( parent );
        } else if ( parent == null || !parent.exists() ) {
            Debug.error("Error! Bad parent when replicating folder chain! " + parent );
        }

        // If the node is not already in this workspace, clone it.
        if ( node.getWorkspace() == null || !node.getWorkspace().exists() || !node.getWorkspace().equals( this ) ) {
            node = findScriptNodeByName( nodeName, this, null );
            if ( node == null || !node.exists() || !this.equals( node.getWorkspace() ) ) {
                newFolder = node.clone(parent);
                newFolder.setWorkspace( this, node.getNodeRef() );
            }
        }

        if ( Debug.isOn() ) Debug.outln( "returning newFolder: " + newFolder );
        return newFolder;
    }

    public WorkspaceNode getCommonParent(WorkspaceNode other) {
        return getCommonParent( this, other );
    }

    public static WorkspaceNode getCommonParent( WorkspaceNode ws1,
                                                 WorkspaceNode ws2 ) {
        Set<WorkspaceNode> parents = new TreeSet<WorkspaceNode>();
        while ( ( ws1 != null || ws2 != null )
                && ( ws1 == null && !ws1.equals( ws2 ) )
                && ( ws1 == null || !parents.contains( ws1 ) )
                && ( ws2 == null || !parents.contains( ws2 ) ) ) {
            if ( ws1 != null ) {
                parents.add( ws1 );
                ws1 = ws1.getParentWorkspace();
            }
            if ( ws2 != null ) {
                parents.add( ws2 );
                ws2 = ws2.getParentWorkspace();
            }
        }
        if ( ws1 != null && ( ws1.equals( ws2 ) || parents.contains( ws1 ) ) ) {
            return ws1;
        }
        if ( ws2 != null && parents.contains( ws2 ) ) {
            return ws2;
        }
        return null;
    }

    public Set< NodeRef > getChangedNodeRefs( Date dateTime ) {
        Set< NodeRef > changedNodeRefs = new TreeSet< NodeRef >(NodeUtil.nodeRefComparator);
        //ResultSet refs = NodeUtil.findNodeRefsByType( getName(), SearchType.WORKSPACE, getServices() );
        //List< EmsScriptNode > nodes = NodeUtil.resultSetToList( refs );
        //NodeUtil.resultSetToList( refs );
        // don't need to findDeleted since its doing a comparison with another workspace - so
        // absence is equivalent to finding deleted
        ArrayList< NodeRef > refs =
                NodeUtil.findNodeRefsByType( getNodeRef().toString(),
                                             SearchType.WORKSPACE.prefix, null,
                                             dateTime, false, true,
                                             getServices(), false );
        changedNodeRefs.addAll( refs );
        //List< EmsScriptNode > nodes = toEmsScriptNodeList( refs );

        // remove commits
        CommitUtil c = new CommitUtil();
        ArrayList< EmsScriptNode > commits = c.getCommits( this, null, getServices(), getResponse() );
        commits.add( c.getCommitPkg( this, null, getServices(), getResponse() ) );
        System.out.println("removing commits: " + commits );
        changedNodeRefs.removeAll( commits );

        return changedNodeRefs;
    }

    public Set< String > getChangedElementIds( Date dateTime ) {
        Set< String > changedElementIds = new TreeSet< String >();
        Set< NodeRef > refs = getChangedNodeRefs( dateTime );
        List< EmsScriptNode > nodes = toEmsScriptNodeList( refs );
        changedElementIds.addAll( EmsScriptNode.getNames( nodes ) );
        return changedElementIds;
    }

    public Set< NodeRef > getChangedNodeRefsWithRespectTo( WorkspaceNode other, Date dateTime ) {
        Set< NodeRef > changedNodeRefs = new TreeSet< NodeRef >(NodeUtil.nodeRefComparator);//getChangedNodeRefs());
        if ( NodeUtil.exists( other ) ) {
            WorkspaceNode targetParent = getCommonParent( other );
            WorkspaceNode parent = this;
            while ( parent != null && !parent.equals( targetParent ) ) {
                changedNodeRefs.addAll( parent.getChangedNodeRefs( dateTime ) );
                parent = parent.getParentWorkspace();
            }
        }
        return changedNodeRefs;
    }

    public Set< String > getChangedElementIdsWithRespectTo( WorkspaceNode other, Date dateTime ) {
        Set< String > changedElementIds = new TreeSet< String >();//getChangedElementIds());
        if ( NodeUtil.exists( other ) ) {
            WorkspaceNode targetParent = getCommonParent( other );
            WorkspaceNode parent = this;
            while ( parent != null && !parent.equals( targetParent ) ) {
                changedElementIds.addAll( parent.getChangedElementIds( dateTime ) );
                parent = parent.getParentWorkspace();
            }
        }
        return changedElementIds;
    }

}
