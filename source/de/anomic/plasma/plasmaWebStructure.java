// plasmaWebStructure.java
// -----------------------------
// (C) 2007 by Michael Peter Christen; mc@anomic.de, Frankfurt a. M., Germany
// first published 15.05.2007 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2006-04-02 22:40:07 +0200 (So, 02 Apr 2006) $
// $LastChangedRevision: 1986 $
// $LastChangedBy: orbiter $
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.plasma;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import de.anomic.kelondro.kelondroBase64Order;
import de.anomic.kelondro.kelondroMicroDate;
import de.anomic.server.serverDate;
import de.anomic.server.serverFileUtils;
import de.anomic.server.logging.serverLog;
import de.anomic.yacy.yacyURL;

public class plasmaWebStructure {

    public static int maxCRLDump = 500000;
    public static int maxCRGDump = 200000;
    public static int maxref = 300; // maximum number of references, to avoid overflow when a large link farm occurs (i.e. wikipedia)
    public static int maxhosts = 8000; // maximum number of hosts in web structure map
    
    private StringBuffer crg;     // global citation references
    private serverLog    log;
    private File         rankingPath, structureFile;
    private String       crlFile, crgFile;
    TreeMap<String, String> structure_old, structure_new; // <b64hash(6)>','<host> to <date-yyyymmdd(8)>{<target-b64hash(6)><target-count-hex(4)>}*
    
    public plasmaWebStructure(serverLog log, File rankingPath, String crlFile, String crgFile, File structureFile) {
        this.log = log;
        this.rankingPath = rankingPath;
        this.crlFile = crlFile;
        this.crgFile = crgFile;
        this.crg = new StringBuffer(maxCRGDump);
        this.structure_old = new TreeMap<String, String>();
        this.structure_new = new TreeMap<String, String>();
        this.structureFile = structureFile;
        
        // load web structure
        Map<String, String> loadedStructure = (this.structureFile.exists()) ? serverFileUtils.loadHashMap(this.structureFile) : new TreeMap<String, String>();
        if (loadedStructure != null) this.structure_old.putAll(loadedStructure);
        
        // delete out-dated entries in case the structure is too big
        if (this.structure_old.size() > maxhosts) {
        	// fill a set with last-modified - dates of the structure
        	TreeSet<String> delset = new TreeSet<String>();
        	String key, value;
        	for (Map.Entry<String, String> entry : this.structure_old.entrySet()) {
        		key = entry.getKey();
        		value = entry.getValue();
        		delset.add(value.substring(0, 8) + key);
        	}
        	int delcount = this.structure_old.size() - (maxhosts * 9 / 10);
        	Iterator<String> j = delset.iterator();
        	while ((delcount > 0) && (j.hasNext())) {
        		this.structure_old.remove(j.next().substring(8));
        		delcount--;
        	}
        }
    }
    
    public Integer[] /*(outlinksSame, outlinksOther)*/ generateCitationReference(plasmaParserDocument document, plasmaCondenser condenser, Date docDate) {
        yacyURL url = document.dc_source();
        
        // generate citation reference
        Map<yacyURL, String> hl = document.getHyperlinks();
        Iterator<yacyURL> it = hl.keySet().iterator();
        String nexturlhash;
        StringBuffer cpg = new StringBuffer(12 * (hl.size() + 1) + 1);
        StringBuffer cpl = new StringBuffer(12 * (hl.size() + 1) + 1);
        String lhp = url.hash().substring(6); // local hash part
        int GCount = 0;
        int LCount = 0;
        while (it.hasNext()) {
            nexturlhash = it.next().hash();
            if (nexturlhash != null) {
                if (nexturlhash.substring(6).equals(lhp)) {
                    // this is a inbound link
                    cpl.append(nexturlhash.substring(0, 6)); // store only local part
                    LCount++;
                } else {
                    // this is a outbound link
                    cpg.append(nexturlhash); // store complete hash
                    GCount++;
                }
            }
        }
        
        // append this reference to buffer
        // generate header info
        String head = url.hash() + "=" +
        kelondroMicroDate.microDateHoursStr(docDate.getTime()) +          // latest update timestamp of the URL
        kelondroMicroDate.microDateHoursStr(System.currentTimeMillis()) + // last visit timestamp of the URL
        kelondroBase64Order.enhancedCoder.encodeLongSmart(LCount, 2) +  // count of links to local resources
        kelondroBase64Order.enhancedCoder.encodeLongSmart(GCount, 2) +  // count of links to global resources
        kelondroBase64Order.enhancedCoder.encodeLongSmart(document.getImages().size(), 2) + // count of Images in document
        kelondroBase64Order.enhancedCoder.encodeLongSmart(0, 2) +       // count of links to other documents
        kelondroBase64Order.enhancedCoder.encodeLongSmart(document.getTextLength(), 3) +   // length of plain text in bytes
        kelondroBase64Order.enhancedCoder.encodeLongSmart(condenser.RESULT_NUMB_WORDS, 3) + // count of all appearing words
        kelondroBase64Order.enhancedCoder.encodeLongSmart(condenser.words().size(), 3) + // count of all unique words
        kelondroBase64Order.enhancedCoder.encodeLongSmart(0, 1); // Flags (update, popularity, attention, vote)
        
        //crl.append(head); crl.append ('|'); crl.append(cpl); crl.append((char) 13); crl.append((char) 10);
        crg.append(head); crg.append('|'); crg.append(cpg); crg.append((char) 13); crg.append((char) 10);
        
        learn(url, cpg);
        
        // if buffer is full, flush it.
        /*
        if (crl.length() > maxCRLDump) {
            flushCitationReference(crl, "crl");
            crl = new StringBuffer(maxCRLDump);
        }
         **/
        if (crg.length() > maxCRGDump) {
            flushCitationReference("crg");
            crg = new StringBuffer(maxCRGDump);
        }
        
        return new Integer[] {new Integer(LCount), new Integer(GCount)};
    }
    
    public void flushCitationReference(String type) {
        if (crg.length() < 12) return;
        String filename = type.toUpperCase() + "-A-" + new serverDate().toShortString(true) + "." + crg.substring(0, 12) + ".cr.gz";
        File path = new File(rankingPath, (type.equals("crl")) ? crlFile : crgFile);
        path.mkdirs();
        File file = new File(path, filename);
        
        // generate header
        StringBuffer header = new StringBuffer(200);
        header.append("# Name=YaCy " + ((type.equals("crl")) ? "Local" : "Global") + " Citation Reference Ticket"); header.append((char) 13); header.append((char) 10);
        header.append("# Created=" + System.currentTimeMillis()); header.append((char) 13); header.append((char) 10);
        header.append("# Structure=<Referee-12>,'=',<UDate-3>,<VDate-3>,<LCount-2>,<GCount-2>,<ICount-2>,<DCount-2>,<TLength-3>,<WACount-3>,<WUCount-3>,<Flags-1>,'|',*<Anchor-" + ((type.equals("crl")) ? "6" : "12") + ">"); header.append((char) 13); header.append((char) 10);
        header.append("# ---"); header.append((char) 13); header.append((char) 10);
        crg.insert(0, header.toString());
        try {
            serverFileUtils.writeAndGZip(crg.toString().getBytes(), file);
            if (this.log.isFine()) log.logFine("wrote citation reference dump " + file.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static int refstr2count(String refs) {
        if ((refs == null) || (refs.length() <= 8)) return 0;
        assert (refs.length() - 8) % 10 == 0;
        return (refs.length() - 8) / 10;
    }
    
    static Map<String, Integer> refstr2map(String refs) {
        if ((refs == null) || (refs.length() <= 8)) return new HashMap<String, Integer>();
        Map<String, Integer> map = new HashMap<String, Integer>();
        String c;
        int refsc = refstr2count(refs);
        for (int i = 0; i < refsc; i++) {
            c = refs.substring(8 + i * 10, 8 + (i + 1) * 10);
            map.put(c.substring(0, 6), new Integer(Integer.parseInt(c.substring(6), 16)));
        }
        return map;
    }
    
    private static String map2refstr(Map<String, Integer> map) {
        StringBuffer s = new StringBuffer(map.size() * 10);
        s.append(serverDate.formatShortDay(new Date()));
        String h;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            s.append(entry.getKey());
            h = Integer.toHexString(entry.getValue().intValue());
            if (h.length() == 0) {
                s.append("0000");
            } else if (h.length() == 1) {
                s.append("000").append(h);
            } else if (h.length() == 2) {
                s.append("00").append(h);
            } else if (h.length() == 3) {
                s.append('0').append(h);
            } else if (h.length() == 4) {
                s.append(h);
            } else {
                s.append("FFFF");
            }
        }
        return s.toString();
    }
    
    public Map<String, Integer> references(String domhash) {
        // returns a map with a domhash(String):refcount(Integer) relation
        assert domhash.length() == 6;
        SortedMap<String, String> tailMap;
        Map<String, Integer> h = new HashMap<String, Integer>();
        synchronized (structure_old) {
            tailMap = structure_old.tailMap(domhash);
            if (!tailMap.isEmpty()) {
                String key = tailMap.firstKey();
                if (key.startsWith(domhash)) {
                    h = refstr2map(tailMap.get(key));
                }
            }
        }
        synchronized (structure_new) {
            tailMap = structure_new.tailMap(domhash);
            if (!tailMap.isEmpty()) {
                String key = tailMap.firstKey();
                if (key.startsWith(domhash)) {
                    h.putAll(refstr2map(tailMap.get(key)));
                }
            }
        }
        return h;
    }
    
    public int referencesCount(String domhash) {
        // returns the number of domains that are referenced by this domhash
        assert domhash.length() == 6 : "domhash = " + domhash;
        SortedMap<String, String> tailMap;
        int c = 0;
        synchronized (structure_old) {
            tailMap = structure_old.tailMap(domhash);
            if (!tailMap.isEmpty()) {
                String key = tailMap.firstKey();
                if (key.startsWith(domhash)) {
                    c = refstr2count(tailMap.get(key));
                }
            }
        }
        synchronized (structure_new) {
            tailMap = structure_new.tailMap(domhash);
            if (!tailMap.isEmpty()) {
                String key = tailMap.firstKey();
                if (key.startsWith(domhash)) {
                    c += refstr2count(tailMap.get(key));
                }
            }
        }
        return c;
    }
    
    public String resolveDomHash2DomString(String domhash) {
        // returns the domain as string, null if unknown
        assert domhash.length() == 6;
        SortedMap<String, String> tailMap;
        synchronized(structure_old) {
            tailMap = structure_old.tailMap(domhash);
            if (!tailMap.isEmpty()) {
                String key = tailMap.firstKey();
                if (key.startsWith(domhash)) {
                    return key.substring(7);
                }
            }
        }
        synchronized(structure_new) {
            tailMap = structure_new.tailMap(domhash);
            if (!tailMap.isEmpty()) {
                String key = tailMap.firstKey();
                if (key.startsWith(domhash)) {
                    return key.substring(7);
                }
            }
        }
        return null;
    }
    
    private void learn(yacyURL url, StringBuffer reference /*string of b64(12digits)-hashes*/) {
        String domhash = url.hash().substring(6);

        // parse the new reference string and join it with the stored references
        Map<String, Integer> refs = references(domhash);
        assert reference.length() % 12 == 0;
        String dom;
        int c;
        for (int i = 0; i < reference.length() / 12; i++) {
            dom = reference.substring(i * 12 + 6, (i + 1) * 12);
            c = 0;
            if (refs.containsKey(dom)) {
                c = (refs.get(dom)).intValue();
            }
            refs.put(dom, new Integer(++c));
        }
        
        // check if the maxref is exceeded
        if (refs.size() > maxref) {
        	int shrink = refs.size() - (maxref * 9 / 10);
			delloop: while (shrink > 0) {
				// shrink the references: the entry with the smallest number of references is removed
				int minrefcount = Integer.MAX_VALUE;
				String minrefkey = null;
				findloop: for (Map.Entry<String, Integer> entry : refs.entrySet()) {
					if (entry.getValue().intValue() < minrefcount) {
						minrefcount = entry.getValue().intValue();
						minrefkey = entry.getKey();
					}
					if (minrefcount == 1) break findloop;
				}
				// remove the smallest
				if (minrefkey == null) break delloop;
				refs.remove(minrefkey);
				shrink--;
			}
		}
        
        // store the map back to the structure
        synchronized(structure_new) {
            structure_new.put(domhash + "," + url.getHost(), map2refstr(refs));
        }
    }
    
    private static final void joinStructure(TreeMap<String, String> into, TreeMap<String, String> from) {
        for (Map.Entry<String, String> e: from.entrySet()) {
            if (into.containsKey(e.getKey())) {
                Map<String, Integer> s0 = refstr2map(into.get(e.getKey()));
                Map<String, Integer> s1 = refstr2map(e.getValue());
                for (Map.Entry<String, Integer> r: s1.entrySet()) {
                    if (s0.containsKey(r.getKey())) {
                        s0.put(r.getKey(), s0.get(r.getKey()).intValue() + r.getValue().intValue());
                    } else {
                        s0.put(r.getKey(), r.getValue().intValue());
                    }
                }
                into.put(e.getKey(), map2refstr(s0));
            } else {
                into.put(e.getKey(), e.getValue());
            }
        }
    }
    
    public void joinOldNew() {
        synchronized(structure_new) {
            joinStructure(this.structure_old, this.structure_new);
            this.structure_new.clear();
        }
    }
    
    public void saveWebStructure() {
        joinOldNew();
        try {
            synchronized(structure_old) {
                serverFileUtils.saveMap(this.structureFile, this.structure_old, "Web Structure Syntax: <b64hash(6)>','<host> to <date-yyyymmdd(8)>{<target-b64hash(6)><target-count-hex(4)>}*");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public String hostWithMaxReferences() {
        // find domain with most references
        String maxhost = null;
        int refsize, maxref = 0;
        joinOldNew();
        synchronized(structure_new) {
            for (Map.Entry<String, String> entry : structure_old.entrySet()) {
                refsize = entry.getValue().length();
                if (refsize > maxref) {
                    maxref = refsize;
                    maxhost = entry.getKey().substring(7);
                }
            }
        }
        return maxhost;
    }
    
    public Iterator<structureEntry> structureEntryIterator(boolean latest) {
        // iterates objects of type structureEntry
        return new structureIterator(latest);
    }
    
    public class structureIterator implements Iterator<structureEntry> {

        private Iterator<Map.Entry<String, String>> i;
        private structureEntry nextentry;
        
        public structureIterator(boolean latest) {
            i = ((latest) ? structure_new : structure_old).entrySet().iterator();
            next0();
        }
        
        public boolean hasNext() {
            return nextentry != null;
        }

        private void next0() {
            Map.Entry<String, String> entry = null;
            String dom = null, ref;
            while (i.hasNext()) {
                entry = i.next();
                dom = entry.getKey();
                if (dom.length() >= 8) break;
                if (!i.hasNext()) {
                    nextentry = null;
                    return;
                }
            }
            if ((entry == null) || (dom == null)) {
                nextentry = null;
                return;
            }
            ref = entry.getValue();
            nextentry = new structureEntry(dom.substring(0, 6), dom.substring(7), ref.substring(0, 8), refstr2map(ref));
        }
        
        public structureEntry next() {
            structureEntry r = nextentry;
            next0();
            return r;
        }

        public void remove() {
            throw new UnsupportedOperationException("not implemented");
        }
        
    }
    
    public class structureEntry {
        public String domhash, domain, date;
        public Map<String, Integer> references;
        public structureEntry(String domhash, String domain, String date, Map<String, Integer> references) {
            this.domhash = domhash;
            this.domain = domain;
            this.date = date;
            this.references = references;
        }
    }
    
    public void close() {
        log.logInfo("Saving Web Structure File");
        saveWebStructure();
    }
}
