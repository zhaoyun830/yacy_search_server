import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.regex.Pattern;

import net.yacy.cora.document.UTF8;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.kelondro.blob.Tables;
import net.yacy.kelondro.blob.Tables.Row;
import net.yacy.kelondro.logging.Log;
import de.anomic.data.UserDB;
import de.anomic.data.ymark.YMarkEntry;
import de.anomic.data.ymark.YMarkTables;
import de.anomic.data.ymark.YMarkTables.TABLES;
import de.anomic.search.Switchboard;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;


public class get_ymark {
	
	private static Switchboard sb = null;
	private static serverObjects prop = null;
	
	public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        sb = (Switchboard) env;
        prop = new serverObjects();
        
        int rp;         // items per page
        int page;                 // page
        int total;
        String sortorder;
        String sortname;
        String qtype;
        String query;
        
        final UserDB.Entry user = sb.userDB.getUser(header);
        final boolean isAdmin = (sb.verifyAuthentication(header, true));
        final boolean isAuthUser = user!= null && user.hasRight(UserDB.AccessRight.BOOKMARK_RIGHT);
    	Iterator<Tables.Row> bookmarks = null;
        
        if(isAdmin || isAuthUser) {
        	final String bmk_user = (isAuthUser ? user.getUserName() : YMarkTables.USER_ADMIN);
        	
            query = ".*";
            qtype = YMarkEntry.BOOKMARK.TITLE.key();
            page = 1;
            rp = 10;
            total = 0;
            sortname = YMarkEntry.BOOKMARK.TITLE.key();
            sortorder = "asc";
        	
            if(post != null) {
                rp = (post.containsKey("rp")) ? post.getInt("rp", 10) : 10;
                page = (post.containsKey("page")) ? post.getInt("page", 1): 1;
                query = (post.containsKey("query")) ? post.get("query", ".*") : ".*";
                qtype = (post.containsKey("qtype")) ? post.get("qtype", YMarkEntry.BOOKMARK.TAGS.key()) : YMarkEntry.BOOKMARK.TAGS.key();
                sortname = (post.containsKey("sortname")) ? post.get("sortname", YMarkEntry.BOOKMARK.TITLE.key()) : YMarkEntry.BOOKMARK.TITLE.key();
                sortorder = (post.containsKey("sortorder")) ? post.get("sortorder", "asc") : "asc";
            } 
            if (qtype.isEmpty())
                qtype = YMarkEntry.BOOKMARK.TITLE.key();
            if (query.isEmpty())
                query = ".*";                
            try {
                final String bmk_table = TABLES.BOOKMARKS.tablename(bmk_user);
                final Collection<Row> result = sb.tables.bookmarks.orderBy(sb.tables.iterator(bmk_table, qtype, Pattern.compile(query)), sortname, sortorder);
                total = result.size();
                bookmarks = result.iterator();
            } catch (IOException e) {
                Log.logException(e);
            }

            
            
            /*
            if (qtype.equals(YMarkEntry.BOOKMARK.TAGS.key()) && !query.isEmpty()) {
	    		final String[] tagArray = YMarkUtil.cleanTagsString(post.get(YMarkEntry.BOOKMARK.TAGS.key())).split(YMarkUtil.TAGS_SEPARATOR);
	    		try {
	    			bookmarks = YMarkTables.getPage(sb.tables.bookmarks.getBookmarksByTag(bmk_user, tagArray), sortname, itemsPerPage, page).iterator();    
				} catch (IOException e) {
					Log.logException(e);
				}
	    	} else {
	    	    try {
                    bookmarks = YMarkTables.getPage(sb.tables.iterator(YMarkTables.TABLES.BOOKMARKS.tablename(bmk_user)), sortname, itemsPerPage, page).iterator();
                } catch (IOException e) {
                    Log.logException(e);
                }
	    	}
	    	*/
	    	/*
	    	if(post.containsKey(YMarkTables.BOOKMARK.FOLDERS.key())) {
	    		final String[] folderArray = YMarkTables.cleanFoldersString(post.get(YMarkTables.BOOKMARK.FOLDERS.key())).split(YMarkTables.TAGS_SEPARATOR);
                try {                	
					if(tags)
						bookmarks.retainAll(sb.tables.bookmarks.folders.getBookmarkIds(bmk_user, folderArray));
					else
						bookmarks.addAll(sb.tables.bookmarks.folders.getBookmarkIds(bmk_user, folderArray));
				} catch (IOException e) {
					Log.logException(e);
				} catch (RowSpaceExceededException e) {
					Log.logException(e);
				}
	    	}
	    	*/
            prop.put("page", page);
            prop.put("total", total);
	    	putProp(bookmarks, rp, page);
	    	
        } else {
        	prop.put(YMarkTables.USER_AUTHENTICATE,YMarkTables.USER_AUTHENTICATE_MSG);
        }
        // return rewrite properties
        return prop;
	}
		
	private static void putProp(final Iterator<Tables.Row> bit, final int rp, final int page) {
	    Tables.Row bmk_row;
	    int count = 0;
        int offset = 0;
        if (page > 1) {
            offset = ((page - 1) * rp) + 1;
        }
        while(count < offset && bit.hasNext()) {
            bmk_row = bit.next();
            count++;
        }
        count = 0;
        while (count < rp && bit.hasNext()) {
            bmk_row = bit.next();
            if (bmk_row != null) {
                
                // put JSON
                prop.put("json_"+count+"_id", count);
                prop.put("json_"+count+"_hash", UTF8.String(bmk_row.getPK()));
                for (YMarkEntry.BOOKMARK bmk : YMarkEntry.BOOKMARK.values()) {
                    if(bmk == YMarkEntry.BOOKMARK.PUBLIC)
                        prop.put("json_"+count+"_"+bmk.key(), bmk_row.get(bmk.key(),bmk.deflt()).equals("true") ? 1 : 0);
                    else
                        prop.putJSON("json_"+count+"_"+bmk.key(), bmk_row.get(bmk.key(),bmk.deflt()));
                }
                prop.put("json_"+count+"_comma", ",");
                
                // put XML
                prop.putXML("xml_"+count+"_id", UTF8.String(bmk_row.getPK()));
                for (YMarkEntry.BOOKMARK bmk : YMarkEntry.BOOKMARK.values()) {
                    prop.putXML("xml_"+count+"_"+bmk.key(), bmk_row.get(bmk.key(),bmk.deflt()));
                }
                
                count++;
            }
        }
        // eliminate the trailing comma for Json output
        prop.put("json_" + (count - 1) + "_comma", "");
        prop.put("json", count);
        prop.put("xml", count);
	}
}
