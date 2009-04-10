/*
 * This file is part of p4nb.
 *
 * p4nb is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * p4nb is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with p4nb.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.heresylabs.netbeans.p4;

import java.awt.Image;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.swing.Action;
import javax.swing.JOptionPane;
import org.heresylabs.netbeans.p4.actions.FileAction;
import org.netbeans.modules.versioning.spi.VCSAnnotator;
import org.netbeans.modules.versioning.spi.VCSAnnotator.ActionDestination;
import org.netbeans.modules.versioning.spi.VCSContext;
import org.netbeans.modules.versioning.spi.VCSInterceptor;
import org.netbeans.modules.versioning.spi.VersioningSystem;
import org.openide.util.NbPreferences;
import org.openide.windows.IOProvider;
import org.openide.windows.InputOutput;
import org.openide.windows.OutputWriter;

/**
 *
 * @author Aekold Helbrass <Helbrass@gmail.com>
 */
public class PerforceVersioningSystem extends VersioningSystem {

    public static final String NAME = "Perforce";
    private static final String KEY_CONNECTIONS = "connections";
    private static final String KEY_PREFERENCES = "preferences";
    private static PerforceVersioningSystem INSTANCE;

    // <editor-fold defaultstate="collapsed" desc=" init block ">
    public static PerforceVersioningSystem getInstance() {
        if (INSTANCE == null) {
            logWarning(PerforceVersioningSystem.class, "PerforceVersioningSystem singleton is null");
        }
        return INSTANCE;
    }

    public PerforceVersioningSystem() {
        synchronized (PerforceVersioningSystem.class) {
            // TODO remove this check in future
            if (INSTANCE != null) {
                logWarning(this, "PerforceVersioningSystem constructed again");
            }
            INSTANCE = this;
        }
        putProperty(PROP_DISPLAY_NAME, NAME);
        putProperty(PROP_MENU_LABEL, NAME);
        init();
    }

    private void init() {
        Preferences preferences = NbPreferences.forModule(getClass());
        loadConnections(preferences);
        String prefs = preferences.get(KEY_PREFERENCES, null);
        if (prefs == null) {
            perforcePreferences = new PerforcePreferences();
        }
        else {
            perforcePreferences = parsePreferences(prefs);
        }
        initPerformanceHacks();
    }

    private void initPerformanceHacks() {
        workspaces = new String[connections.size()];
        for (int i = 0; i < connections.size(); i++) {
            Connection connection = connections.get(i);
            workspaces[i] = perforcePreferences.isCaseSensetiveWorkspaces() ? connection.getWorkspacePath() : connection.getWorkspacePath().toLowerCase();
        }
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc=" workspaces performance hack ">
    private String[] workspaces;
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc=" VersioningSystem implementation ">
    private Annotator annotator = new Annotator();
    private Interceptor interceptor = new Interceptor();

    @Override
    public void getOriginalFile(File workingCopy, File originalFile) {
        // TODO check if p4 can overwrite already existing and if JVM can get another file with same filename
        String originalPath;
        try {
            originalPath = originalFile.getCanonicalPath();
        }
        catch (Exception e) {
            originalPath = originalFile.getAbsolutePath();
        }
        wrapper.execute("print -o \"" + originalPath + "\" -q", workingCopy);
    }

    @Override
    public File getTopmostManagedAncestor(File file) {
        Connection c = getConnectionForFile(file);
        if (c == null) {
            return null;
        }
        return new File(c.getWorkspacePath());
    }

    @Override
    public VCSAnnotator getVCSAnnotator() {
        return annotator;
    }

    @Override
    public VCSInterceptor getVCSInterceptor() {
        return interceptor;
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc=" connections ">
    private List<Connection> connections = new ArrayList<Connection>();

    public List<Connection> getConnections() {
        return new ArrayList<Connection>(connections);
    }

    public void setConnections(List<Connection> connections) {
        this.connections = connections;
        saveConnections(NbPreferences.forModule(getClass()));
        initPerformanceHacks();
    }

    private void loadConnections(Preferences prefs) {
        // TODO think about synchronisation
        List<String> connectionsStrings = getStringList(prefs, KEY_CONNECTIONS);
        if (connectionsStrings == null || connectionsStrings.isEmpty()) {
            return;
        }
        List<Connection> conns = new ArrayList<Connection>(connectionsStrings.size());
        for (int i = 0; i < connectionsStrings.size(); i++) {
            String string = connectionsStrings.get(i);
            conns.add(parseConnection(string));
        }
        connections = conns;
    }

    private void saveConnections(Preferences prefs) {
        List<String> conns = new ArrayList<String>(connections.size());
        for (int i = 0; i < connections.size(); i++) {
            conns.add(getConnectionAsString(connections.get(i)));
        }
        putStringList(prefs, KEY_CONNECTIONS, conns);
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc=" VCS logic ">
    private PerforcePreferences perforcePreferences;

    public PerforcePreferences getPerforcePreferences() {
        return new PerforcePreferences(perforcePreferences.isInterceptEdit(), perforcePreferences.isInterceptDelete(),
                perforcePreferences.isInterceptAdd(), perforcePreferences.isConfirmEdit(),
                perforcePreferences.isCaseSensetiveWorkspaces(), perforcePreferences.isPrintOutput());
    }

    public void setPerforcePreferences(PerforcePreferences perforcePreferences) {
        this.perforcePreferences = perforcePreferences;
        Preferences preferences = NbPreferences.forModule(getClass());
        preferences.put(KEY_PREFERENCES, getPreferencesAsString(perforcePreferences));
        initPerformanceHacks();
    }

    private Action[] getPerforceActions(VCSContext context, ActionDestination destination) {
        if (destination == ActionDestination.PopupMenu) {
            return asArray(
                    new FileAction(context, "edit", "Edit"),
                    new FileAction(context, "sync", "Sync"),
                    new FileAction(context, "sync -f", "Sync Force"),
                    new FileAction(context, "revert", "Revert"),
                    null,
                    new FileAction(context, "add", "Add"),
                    new FileAction(context, "delete", "Delete"));
        }
        // if we are still here - it's main menu
        return asArray(
                new FileAction(context, "edit", "Edit"),
                new FileAction(context, "sync", "Sync"),
                new FileAction(context, "sync -f", "Sync Force"),
                new FileAction(context, "revert", "Revert"),
                null,
                new FileAction(context, "add", "Add"),
                new FileAction(context, "delete", "Delete"));
    }

    public Connection getConnectionForFile(File file) {
        if (file == null) {
            return null;
        }
        String filePath;
        try {
            filePath = file.getCanonicalPath();
        }
        catch (Exception e) {
            filePath = file.getAbsolutePath();
        }

        if (!perforcePreferences.isCaseSensetiveWorkspaces()) {
            filePath = filePath.toLowerCase();
        }

        for (int i = 0; i < workspaces.length; i++) {
            if (filePath.startsWith(workspaces[i])) {
                // workspaces and connections must have same indexes
                return connections.get(i);
            }
        }
        return null;
    }

    private CliWrapper wrapper = new CliWrapper();
    private FileStatusProvider fileStatusProvider = new FileStatusProvider();

    public CliWrapper getWrapper() {
        return wrapper;
    }

    private void edit(File file) {
        wrapper.execute("edit", file);
    }

    private void add(File file) {
        // TODO add status check here
        wrapper.execute("add", file);
    }

    private void delete(File file) {
        FileStatus fs = fileStatusProvider.getFileStatusNow(file);
        // add other statuses checks
        if (fs == null) {
            return;
        }
        wrapper.execute("delete", file);
    }

    private void revert(File file) {
        wrapper.execute("revert", file);
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc=" static util methods ">
    /**
     * Sorry NB guys, but "friends only" restriction for Util classes is not right!
     */
    private static List<String> getStringList(Preferences prefs, String key) {
        List<String> retval = new ArrayList<String>();
        try {
            String[] keys = prefs.keys();
            for (int i = 0; i < keys.length; i++) {
                String k = keys[i];
                if (k != null && k.startsWith(key)) {
                    int idx = Integer.parseInt(k.substring(k.lastIndexOf('.') + 1));
                    retval.add(idx + "." + prefs.get(k, null));
                }
            }
            List<String> rv = new ArrayList<String>(retval.size());
            rv.addAll(retval);
            for (String s : retval) {
                int pos = s.indexOf('.');
                int index = Integer.parseInt(s.substring(0, pos));
                rv.set(index, s.substring(pos + 1));
            }
            return rv;
        }
        catch (Exception ex) {
            Logger.getLogger(PerforceVersioningSystem.class.getName()).log(Level.INFO, null, ex);
            return new ArrayList<String>(0);
        }
    }

    /**
     * Sorry NB guys, but "friends only" restriction for Util classes is not right!
     */
    private static void putStringList(Preferences prefs, String key, List<String> value) {
        try {
            String[] keys = prefs.keys();
            for (int i = 0; i < keys.length; i++) {
                String k = keys[i];
                if (k != null && k.startsWith(key + ".")) {
                    prefs.remove(k);
                }
            }
            int idx = 0;
            for (String s : value) {
                prefs.put(key + "." + idx++, s);
            }
        }
        catch (BackingStoreException ex) {
            Logger.getLogger(PerforceVersioningSystem.class.getName()).log(Level.INFO, null, ex);
        }
    }

    private static final String RC_DELIMITER = "~=~";

    private static String getConnectionAsString(Connection connection) {
        StringBuilder sb = new StringBuilder();
        sb.append(connection.getServer());
        sb.append(RC_DELIMITER);
        sb.append(connection.getUser());
        sb.append(RC_DELIMITER);
        sb.append(connection.getClient());
        sb.append(RC_DELIMITER);
        sb.append(connection.getPassword());
        sb.append(RC_DELIMITER);
        sb.append(connection.getWorkspacePath());
        return sb.toString();
    }

    private static Connection parseConnection(String string) {
        String[] lines = string.split(RC_DELIMITER);
        return new Connection(lines[0], lines[1], lines[2], lines[3], lines[4]);
    }

    private static String getPreferencesAsString(PerforcePreferences p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.isInterceptAdd() ? 't' : 'f');
        sb.append(p.isInterceptDelete() ? 't' : 'f');
        sb.append(p.isInterceptEdit() ? 't' : 'f');
        sb.append(p.isConfirmEdit() ? 't' : 'f');
        sb.append(p.isCaseSensetiveWorkspaces() ? 't' : 'f');
        sb.append(p.isPrintOutput() ? 't' : 'f');
        return sb.toString();
    }

    private static PerforcePreferences parsePreferences(String s) {
        PerforcePreferences p = new PerforcePreferences();
        p.setInterceptAdd(s.charAt(0) == 't');
        p.setInterceptDelete(s.charAt(1) == 't');
        p.setInterceptEdit(s.charAt(2) == 't');
        p.setConfirmEdit(s.charAt(3) == 't');
        p.setCaseSensetiveWorkspaces(s.charAt(4) == 't');
        p.setPrintOutput(s.charAt(5) == 't');
        return p;
    }

    public static void logError(Object caller, Throwable e) {
        Logger.getLogger(caller.getClass().getName()).log(Level.SEVERE, e.getMessage(), e);
    }

    public static void logWarning(Object caller, String warning) {
        Logger.getLogger(caller.getClass().getName()).log(Level.WARNING, warning);
    }

    public static void print(String message) {
        print(message, false);
    }

    public static void print(String message, boolean error) {

        // checking for printing preferences:
        if (!getInstance().getPerforcePreferences().isPrintOutput()) {
            return;
        }

        String m;
        int passFlagIndex = message.indexOf(" -P ");
        if (passFlagIndex >= 0) {
            int passIndex = passFlagIndex + 4;
            int spaceIndex = message.indexOf(' ', passIndex);
            StringBuilder sb = new StringBuilder(message.length());
            sb.append(message, 0, passIndex);
            sb.append("********");
            sb.append(message, spaceIndex, message.length());
            m = sb.toString();
        }
        else {
            m = message;
        }

        InputOutput io = IOProvider.getDefault().getIO("Perforce", false);
        OutputWriter out = error ? io.getErr() : io.getOut();
        out.print('[');
        out.print(getTime());
        out.print("] ");
        out.println(m);
        out.flush();
    }

    private static final Date currentDate = new Date();
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS");

    private static String getTime() {
        synchronized (dateFormat) {
            currentDate.setTime(System.currentTimeMillis());
            return dateFormat.format(currentDate);
        }
    }

    /**
     * Utility method to convert vararg to array
     */
    private static <T> T[] asArray(T... arg) {
        return arg;
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc=" internal classes ">
    private class Annotator extends VCSAnnotator {

        @Override
        public Image annotateIcon(Image icon, VCSContext context) {
            // TODO implement
            return super.annotateIcon(icon, context);
        }

        @Override
        public String annotateName(String name, VCSContext context) {
            // TODO implement
            return super.annotateName(name, context);
        }

        @Override
        public Action[] getActions(VCSContext context, ActionDestination destination) {
            return getPerforceActions(context, destination);
        }

    }

    private class Interceptor extends VCSInterceptor {

        @Override
        public boolean isMutable(File file) {
            // original check for readonly does not work for Perforce:
            if (perforcePreferences.isInterceptEdit()) {
                return true;
            }
            return super.isMutable(file);
        }

        @Override
        public boolean beforeDelete(File file) {
            FileStatus fs = fileStatusProvider.getFileStatusNow(file);
            // if fs != null - it's revisioned file
            return fs != null;
        }

        @Override
        public void doDelete(File file) throws IOException {
            int res = JOptionPane.showConfirmDialog(null, "Are you sure you want to delete " + file.getName(), "Delete Confirmation", JOptionPane.YES_NO_OPTION);
            if (res == JOptionPane.NO_OPTION) {
                return;
            }
            FileStatus fs = fileStatusProvider.getFileStatusNow(file);
            if (fs == null) {
                logWarning(this, file.getName() + " is not revisioned. Should not be deleted by p4nb");
            }

            // if file already in edit or add mode - revert it first
            if (fs.getAction() != FileStatusProvider.ACTION_NONE) {
                revert(file);
            }
            delete(file);
        }

        @Override
        public boolean beforeMove(File from, File to) {
            return super.beforeMove(from, to);
        }

        @Override
        public void doMove(File from, File to) throws IOException {
            super.doMove(from, to);
        }

        @Override
        public void afterMove(File from, File to) {
            super.afterMove(from, to);
        }

        @Override
        public void afterCreate(File file) {
            if (perforcePreferences.isInterceptAdd()) {
                add(file);
            }
        }

        @Override
        public void beforeEdit(File file) {
            if (file.canWrite()) {
                return;
            }
            if (perforcePreferences.isConfirmEdit()) {
                int res = JOptionPane.showConfirmDialog(null, "Are you sure you want to \"p4 edit\" file " + file.getName(), "Edit Confirmation", JOptionPane.YES_NO_OPTION);
                if (res == JOptionPane.NO_OPTION) {
                    return;
                }
            }
            edit(file);
        }

    }
    // </editor-fold>
}