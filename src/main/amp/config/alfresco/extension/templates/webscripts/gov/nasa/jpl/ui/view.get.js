<import resource="classpath:alfresco/extension/js/json2.js">
<import resource="classpath:alfresco/extension/js/utils.js">
<import resource="classpath:alfresco/extension/js/artifact_utils.js">
<import resource="classpath:alfresco/extension/js/view_utils.js">

var modelFolder = companyhome.childByNamePath("Sites/europa/ViewEditor/model");
var snapshotFolder = companyhome.childByNamePath("Sites/europa/ViewEditor/snapshots");
var viewid = url.templateArgs.viewid;
var product = false;
var info = {};

var elements = [];
var seen = [];
var views = [];
var view2view = {};

function main() {
	var topview = modelFolder.childByNamePath(viewid);
	if (topview == null) {
		status.code = 404;
	} else {
		if (topview.properties["view:product"])
			product = true;
		if (product) {
			view2view = JSON.parse(topview.properties["view:view2viewJson"]);
			var noSections = JSON.parse(topview.properties["view:noSectionsJson"]);
			for (var viewmdid in view2view) {
				var view = modelFolder.childByNamePath(viewmdid);
				if (view == null) {
					status.code = 404;
					return;
				}
				var viewinfo = handleView(view, seen, elements, views, view2view);
				if (noSections.indexOf(viewmdid) >= 0)
					viewinfo.noSection = true;
				else
					viewinfo.noSection = false;
			}
		} else {
			handleView(topview, seen, elements, views, view2view);
		}
		info['snapshots'] = getSnapshots(topview);
	}
	info['elements'] = elements;
	info['view2view'] = view2view;
	info['views'] = views;
	info['rootView'] = viewid;
	info['user'] = person.properties['cm:userName'];
	info['snapshot'] = false;
}

if (UserUtil.hasWebScriptPermissions()) {
    status.code = 200;
    main();
} else {
    status.code = 401;
}

var response;
if (status.code == 200) {
    response = toJson(info);
} else {
    switch(status.code) {
    case 401:
        response = "unauthorized";
        break;
    default:
        response = "NotFound";
        break;
    }
    status.redirect = true;
    status.message = response;
}
model['res'] = response;