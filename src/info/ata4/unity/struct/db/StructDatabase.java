/*
 ** 2013 August 10
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.unity.struct.db;

import info.ata4.unity.asset.AssetFile;
import info.ata4.unity.struct.FieldType;
import info.ata4.unity.struct.TypeTree;
import info.ata4.util.collection.Pair;
import info.ata4.util.io.DataInputReader;
import info.ata4.util.io.DataOutputWriter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;

/**
 *
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class StructDatabase {
    
    private static final Logger L = Logger.getLogger(StructDatabase.class.getName());
    private static final int VERSION = 1;
    private static final String FILENAME = "structdb.dat";
    
    private static StructDatabase instance;

    public static StructDatabase getInstance() {
        if (instance == null) {
            instance = new StructDatabase();
        }
        return instance;
    }
    
    private FieldTypeMap ftm = new FieldTypeMap();
    private int learned;
    
    private StructDatabase() {
        load();
    }
    
    public int getLearned() {
        return learned;
    }
    
    public FieldTypeMap getFieldTypeMap() {
        return ftm;
    }
    
    private void load() {
        L.info("Loading struct database");
        
        // read database file, external or internal otherwise
        InputStream is;
        try {
            File dbFile = new File(FILENAME);
            String dbPath = "/resources/" + FILENAME;

            if (dbFile.exists()) {
                is = FileUtils.openInputStream(dbFile);
            } else {
                is = getClass().getResourceAsStream(dbPath);
            }
            
            if (is == null) {
                throw new IOException("Internal database not found");
            }
        } catch (Exception ex) {
            L.log(Level.SEVERE, "Can't open struct database", ex);
            return;
        }

        try (BufferedInputStream bis = new BufferedInputStream(is)) {
            DataInputReader in = new DataInputReader(new DataInputStream(bis));

            // read header
            int version = in.readInt();

            if (version != VERSION) {
                throw new RuntimeException("Wrong database version");
            }

            // read field node table
            int fieldNodeSize = in.readInt();
            List<FieldType> fieldNodes = new ArrayList<>(fieldNodeSize);

            for (int i = 0; i < fieldNodeSize; i++) {
                FieldType fieldNode = new FieldType();
                fieldNode.read(in);
                fieldNodes.add(fieldNode);
            }

            // read revision string table
            int revisionSize = in.readInt();
            List<String> revisions = new ArrayList<>(revisionSize);

            for (int i = 0; i < revisionSize; i++) {
                revisions.add(in.readStringNull());
            }

            // read mapping data
            int fieldNodeKeySize = in.readInt();

            for (int i = 0; i < fieldNodeKeySize; i++) {
                int index = in.readInt();
                int classID = in.readInt();
                int revisionIndex = in.readInt();
                String revision = revisions.get(revisionIndex);
                FieldType fieldNode = fieldNodes.get(index);

                ftm.add(classID, revision, fieldNode);
            }
        } catch (IOException ex) {
            L.log(Level.SEVERE, "Can't read struct database", ex);
        }
    }
    
    private void save() {
        L.info("Saving struct database");
        
        // write database file
        File dbFile = new File(FILENAME);
        try (BufferedOutputStream bos = new BufferedOutputStream(FileUtils.openOutputStream(dbFile))) {
            DataOutputWriter out = new DataOutputWriter(new DataOutputStream(bos));
            
            // write header
            out.writeInt(VERSION);

            // write field node table
            Set<FieldType> fieldNodes = new HashSet<>(ftm.values());
            Map<FieldType, Integer> fieldNodeMap = new HashMap<>();

            out.writeInt(fieldNodes.size());

            int index = 0;
            for (FieldType fieldNode : fieldNodes) {
                fieldNodeMap.put(fieldNode, index++);
                fieldNode.write(out);
            }

            // write revision string table
            Set<String> revisions = new HashSet<>();
            Map<String, Integer> revisionMap = new HashMap<>();

            for (Map.Entry<Pair<Integer, String>, FieldType> entry : ftm.entrySet()) {
                revisions.add(entry.getKey().getRight());
            }

            out.writeInt(revisions.size());

            index = 0;
            for (String revision : revisions) {
                revisionMap.put(revision, index++);
                out.writeStringNull(revision);
            }

            // write mapping data
            out.writeInt(ftm.entrySet().size());

            for (Map.Entry<Pair<Integer, String>, FieldType> entry : ftm.entrySet()) {
                index = fieldNodeMap.get(entry.getValue());
                Pair<Integer, String> fieldNodeKey = entry.getKey();

                int classID = fieldNodeKey.getLeft();
                String revision = fieldNodeKey.getRight();

                out.writeInt(index);
                out.writeInt(classID);
                out.writeInt(revisionMap.get(revision));
            }
        } catch (IOException ex) {
            L.log(Level.SEVERE, "Can't write struct database", ex);
        }
    }
    
    public void fill(AssetFile asset) {
        TypeTree typeTree = asset.getTypeTree();
        Set<Integer> classIDs = asset.getClassIDs();
        
        if (typeTree.revision == null) {
            L.warning("typeTree.revision = null");
            return;
        }
        
        for (Integer classID : classIDs) {
            FieldType ft = ftm.get(classID, typeTree.revision, false);
            if (ft != null) {
                typeTree.put(classID, ft);
            }
        }
        
        // don't include the struct when saving
        typeTree.setStandalone(true);
    }
    
    public int learn(AssetFile asset) {
        TypeTree typeTree = asset.getTypeTree();
        Set<Integer> classIDs = asset.getClassIDs();
        
        if (typeTree.isStandalone()) {
            L.info("No structure data available");
            return 0;
        }
        
        // older file formats don't contain the revision in the header, override
        // it manually here
        if (typeTree.revision == null) {
            //typeTree.revision = "2.6.0f7";
            L.warning("typeTree.revision = null");
            return 0;
        }
        
        int learnedNew = 0;
        
        // merge the TypeTree map with the database field map
        for (Integer classID : classIDs) {
            FieldType fieldType = typeTree.get(classID);

            if (fieldType == null) {
                continue;
            }
            
            FieldType fieldTypeMapped = ftm.get(classID, typeTree.revision);

            if (fieldTypeMapped == null) {
                fieldTypeMapped = fieldType;
                ftm.add(classID, typeTree.revision, fieldTypeMapped);
                learnedNew++;
            }

            // check the hashes, they must be identical at this point
            int hash1 = fieldType.hashCode();
            int hash2 = fieldTypeMapped.hashCode();

            if (hash1 != hash2) {
                L.log(Level.WARNING, "Database hash mismatch for {0}: {1} != {2}", new Object[] {fieldTypeMapped.type, hash1, hash2});
            }

            if (ClassID.getInstance().getNameForID(classID) == null) {
                L.log(Level.WARNING, "Unknown ClassID {0}, suggested name: {1}", new Object[] {classID, fieldType.type});
            }
        }
        
        learned += learnedNew;
        
        return learnedNew;
    }
    
    public void update() {
        if (learned > 0) {
            L.log(Level.INFO, "Adding {0} new struct(s) to database", learned);
            save();
            learned = 0;
        }
    }
}
