/**
 * Inventory Waste BASE
 *
 * @author Michael Torres Cuison
 * @since 2018.10.10
 */
package org.rmj.purchasing.agent;

import com.mysql.jdbc.Connection;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.data.JsonDataSource;
import net.sf.jasperreports.view.JasperViewer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.rmj.appdriver.constants.EditMode;
import org.rmj.appdriver.constants.RecordStatus;
import org.rmj.appdriver.constants.TransactionStatus;
import org.rmj.appdriver.GRider;
import org.rmj.appdriver.iface.GEntity;
import org.rmj.appdriver.MiscUtil;
import org.rmj.appdriver.SQLUtil;
import org.rmj.appdriver.agentfx.CommonUtils;
import org.rmj.appdriver.agentfx.ShowMessageFX;
import org.rmj.appdriver.agentfx.ui.showFXDialog;
import org.rmj.appdriver.constants.UserRight;
import org.rmj.appdriver.agentfx.callback.IMasterDetail;
import org.rmj.cas.client.base.XMClient;
import org.rmj.cas.inventory.base.Inventory;
import org.rmj.cas.purchasing.pojo.UnitPODetail;
import org.rmj.cas.purchasing.pojo.UnitPOMaster;
import org.rmj.cas.purchasing.pojo.UnitPOReceivingDetailOthers;
import org.rmj.lp.parameter.agent.XMBranch;
import org.rmj.lp.parameter.agent.XMInventoryType;
import org.rmj.lp.parameter.agent.XMSupplier;
import org.rmj.lp.parameter.agent.XMTerm;
import org.rmj.purchasing.statusChange.StatusChange;

public class PurchaseOrders {

    private final String MODULENAME = "PurchaseOrder";

    public PurchaseOrders(GRider foGRider, String fsBranchCD, boolean fbWithParent) {
        this.poGRider = foGRider;

        if (foGRider != null) {
            this.pbWithParent = fbWithParent;
            this.psBranchCd = fsBranchCD;

            this.psUserIDxx = foGRider.getUserID();
            pnEditMode = EditMode.UNKNOWN;
        }
    }

    public boolean BrowseRecord(String fsValue, boolean fbByCode) {
        String lsHeader = "Trans No»Supplier»Refer No»Inv. Type»Date";
        String lsColName = "sTransNox»sClientNm»sReferNox»sDescript»dTransact";
        String lsColCrit = "a.sTransNox»d.sClientNm»a.sReferNox»c.sDescript»a.dTransact";
        String lsSQL = getSQL_POMaster();
        JSONObject loJSON;
        System.out.println(lsSQL);
        loJSON = showFXDialog.jsonSearch(poGRider,
                lsSQL,
                fsValue,
                lsHeader,
                lsColName,
                lsColCrit,
                fbByCode ? 2 : 1);

        if (loJSON == null) {
            return false;
        } else {
            return openTransaction((String) loJSON.get("sTransNox"));
        }
    }

    public boolean addDetail() {
        if (paDetail.isEmpty()) {
            paDetail.add(new UnitPODetail());

            paDetailOthers.add(new UnitPOReceivingDetailOthers());
        } else {
            if (!paDetail.get(ItemCount() - 1).getStockID().equals("")
                    && paDetail.get(ItemCount() - 1).getQuantity().doubleValue() != 0.00) {
                paDetail.add(new UnitPODetail());

                paDetailOthers.add(new UnitPOReceivingDetailOthers());
            }
        }
        return true;
    }

    public boolean deleteDetail(int fnRow) {
        paDetail.remove(fnRow);
        paDetailOthers.remove(fnRow);

        if (paDetail.isEmpty()) {
            paDetail.add(new UnitPODetail());
            paDetailOthers.add(new UnitPOReceivingDetailOthers());
        }

        poData.setTranTotal(computeTotal());
        return true;
    }

    public void setDetail(int fnRow, int fnCol, Object foData) {
        if (pnEditMode != EditMode.UNKNOWN) {
            // Don't allow specific fields to assign values
            if (!(fnCol == poDetail.getColumn("sTransNox")
                    || fnCol == poDetail.getColumn("nEntryNox")
                    || fnCol == poDetail.getColumn("dModified"))) {

                if (fnCol == poDetail.getColumn("nQuantity")) {
                    if (foData instanceof Double) {

                        paDetail.get(fnRow).setValue(fnCol, foData);
                        addDetail();
//                        if (Double.valueOf(foData.toString()) > Double.valueOf(paDetailOthers.get(fnRow).getValue("nQtyOnHnd").toString())){
//                            ShowMessageFX.Information("Quantity to deduct is greater than the quantity on hand.", MODULENAME, "Invalid quantity");
//                        } else {
//                            paDetail.get(fnRow).setValue(fnCol, foData);
//                            addDetail();
//                        }

                    } else {
                        paDetail.get(fnRow).setValue(fnCol, 0);
                    }
                } else if (fnCol == poDetail.getColumn("nUnitPrce")) {
                    if (foData instanceof Number) {
                        paDetail.get(fnRow).setValue(fnCol, foData);
                    } else {
                        paDetail.get(fnRow).setValue(fnCol, 0.00);
                    }

                } else if (fnCol == 100) {
                    paDetailOthers.get(fnRow).setValue("xBarCodex", foData);
                } else if (fnCol == 101) {
                    paDetailOthers.get(fnRow).setValue("xDescript", foData);
                } else {
                    paDetail.get(fnRow).setValue(fnCol, foData);
                }

                DetailRetreived(fnCol);
                if (fnCol == Integer.parseInt(String.valueOf(paDetail.get(fnRow).getColumn("nQuantity")))
                        || fnCol == Integer.parseInt(String.valueOf(paDetail.get(fnRow).getColumn("nUnitPrce")))) {
                    poData.setTranTotal(computeTotal());
                    MasterRetreived(9);
                }
            }

        }
    }

    public void setDetail(int fnRow, String fsCol, Object foData) {
        setDetail(fnRow, poDetail.getColumn(fsCol), foData);
    }

    public Object getDetail(int fnRow, int fnCol) {
        if (pnEditMode == EditMode.UNKNOWN) {
            return null;
        } else {
            return paDetail.get(fnRow).getValue(fnCol);
        }
    }

    public Object getDetail(int fnRow, String fsCol) {
        return getDetail(fnRow, poDetail.getColumn(fsCol));
    }

    public Object getDetailOthers(int fnRow, String fsCol) {
        switch (fsCol) {
            case "sStockIDx":
            case "xBarCodex":
            case "xDescript":
            case "xQtyOnHnd":
            case "sMeasurNm":
                return paDetailOthers.get(fnRow).getValue(fsCol);
            default:
                return null;
        }
    }

    public boolean newTransaction() {
        Connection loConn = null;
        loConn = setConnection();

        poData = new UnitPOMaster();
        poData.setTransNox(MiscUtil.getNextCode(poData.getTable(), "sTransNox", true, loConn, psBranchCd));
        poData.setDateTransact(poGRider.getServerDate());

        //init detail
        paDetail = new ArrayList<>();
        paDetailOthers = new ArrayList<>(); //detail other info storage

        pnEditMode = EditMode.ADDNEW;

        addDetail();
        return true;
    }

    public boolean openTransaction(String fsTransNox) {
        poData = loadTransaction(fsTransNox);

        if (poData != null) {
            paDetail = loadTransactionDetail(fsTransNox);
        } else {
            setMessage("Unable to load transaction.");
            return false;
        }

        pnEditMode = EditMode.READY;
        return true;
    }

    public UnitPOMaster loadTransaction(String fsTransNox) {
        UnitPOMaster loObject = new UnitPOMaster();

        Connection loConn = null;
        loConn = setConnection();

        String lsSQL = MiscUtil.addCondition(getSQ_Master(), "sTransNox = " + SQLUtil.toSQL(fsTransNox));
        ResultSet loRS = poGRider.executeQuery(lsSQL);

        try {
            if (!loRS.next()) {
                setMessage("No Record Found");
            } else {
                //load each column to the entity
                for (int lnCol = 1; lnCol <= loRS.getMetaData().getColumnCount(); lnCol++) {
                    loObject.setValue(lnCol, loRS.getObject(lnCol));
                }
            }
        } catch (SQLException ex) {
            setErrMsg(ex.getMessage());
        } finally {
            MiscUtil.close(loRS);
            if (!pbWithParent) {
                MiscUtil.close(loConn);
            }
        }

        return loObject;
    }

    public ResultSet getExpiration(String fsStockIDx) {
        String lsSQL = "SELECT * FROM Inv_Master_Expiration"
                + " WHERE sStockIDx = " + SQLUtil.toSQL(fsStockIDx)
                + " AND sBranchCd = " + SQLUtil.toSQL(psBranchCd)
                + " AND nQtyOnHnd > 0"
                + " ORDER BY dExpiryDt";

        ResultSet loRS = poGRider.executeQuery(lsSQL);

        return loRS;
    }

    private ArrayList<UnitPODetail> loadTransactionDetail(String fsTransNox) {
        UnitPODetail loOcc = null;
        UnitPOReceivingDetailOthers loOth = null;
        Connection loConn = null;
        loConn = setConnection();

        ArrayList<UnitPODetail> loDetail = new ArrayList<>();
        paDetailOthers = new ArrayList<>(); //reset detail others

        ResultSet loRS = poGRider.executeQuery(
                MiscUtil.addCondition(getSQ_Detail(),
                        "sTransNox = " + SQLUtil.toSQL(fsTransNox)));
        try {
            for (int lnCtr = 1; lnCtr <= MiscUtil.RecordCount(loRS); lnCtr++) {
                loRS.absolute(lnCtr);

                loOcc = new UnitPODetail();
                loOcc.setValue("sTransNox", loRS.getString("sTransNox"));
                loOcc.setValue("nEntryNox", loRS.getInt("nEntryNox"));
                loOcc.setValue("sStockIDx", loRS.getString("sStockIDx"));
                loOcc.setValue("nQuantity", loRS.getDouble("nQuantity"));
                loOcc.setValue("nUnitPrce", loRS.getDouble("nUnitPrce"));
                loOcc.setValue("nReceived", loRS.getDouble("nReceived"));
                loOcc.setValue("nCancelld", loRS.getDouble("nCancelld"));
                loOcc.setValue("dModified", loRS.getDate("dModified"));
                loOcc.setValue("nQtyOnHnd", loRS.getDouble("nQtyOnHnd"));
                loOcc.setValue("sBrandNme", loRS.getString("sBrandNme"));
                loDetail.add(loOcc);

                loOth = new UnitPOReceivingDetailOthers();
                loOth.setValue("sStockIDx", loRS.getString("sStockIDx"));
                loOth.setValue("sBarCodex", loRS.getString("sBarCodex"));
                loOth.setValue("sDescript", loRS.getString("sDescript"));
                loOth.setValue("xQtyOnHnd", loRS.getDouble("nQtyOnHnd"));
                loOth.setValue("nQtyOnHnd", loRS.getDouble("nQtyOnHnd"));
                if (loRS.getString("sMeasurNm") != null) {
                    loOth.setValue("sMeasurNm", loRS.getString("sMeasurNm"));
                } else {
                    loOth.setValue("sMeasurNm", "");
                }
                paDetailOthers.add(loOth);
            }
        } catch (SQLException e) {
            //log error message
            setErrMsg(e.getMessage());
            return null;
        }

        return loDetail;
    }

    public boolean updateRecord() {
        if (pnEditMode != EditMode.READY) {
            return false;
        } else {
            pnEditMode = EditMode.UPDATE;
            return true;
        }
    }

    private double computeTotal() {
        double lnTranTotal = 0;
        for (int lnCtr = 0; lnCtr <= ItemCount() - 1; lnCtr++) {
            lnTranTotal += Double.valueOf(String.valueOf(getDetail(lnCtr, "nQuantity"))) * Double.valueOf(String.valueOf(getDetail(lnCtr, "nUnitPrce")));
        }

        return lnTranTotal;
    }
    
    public boolean saveTransaction() {
    String lsSQL = "";
    boolean lbUpdate = false;

    UnitPOMaster loOldEnt = null;
    UnitPOMaster loNewEnt = null;
    UnitPOMaster loResult = null;

    // Check for the value of poData
    if (!(poData instanceof UnitPOMaster)) {
        setErrMsg("Invalid Entity Passed as Parameter");
        return false;
    }

    // Typecast the Entity to this object
    loNewEnt = (UnitPOMaster) poData;

    // Test if entry is ok
    if (loNewEnt.getBranchCd() == null || loNewEnt.getBranchCd().isEmpty()) {
        setMessage("No branch detected.");
        return false;
    }

    if (loNewEnt.getDateTransact() == null) {
        setMessage("No transact date detected.");
        return false;
    }

    if (loNewEnt.getCompanyID() == null || loNewEnt.getCompanyID().isEmpty()) {
        setMessage("No company detected.");
        return false;
    }

    if (loNewEnt.getDestinat() == null || loNewEnt.getDestinat().isEmpty()) {
        setMessage("No destination detected.");
        return false;
    }

    if (loNewEnt.getSupplier() == null || loNewEnt.getSupplier().isEmpty()) {
        setMessage("No supplier detected.");
        return false;
    }

    if (getDetail(ItemCount() - 1, "sStockIDx").equals("")) {
        deleteDetail(ItemCount() - 1);
    }

    if (ItemCount() <= 0) {
        setMessage("Unable to save no item record.");
        return false;
    }

    loNewEnt.setTranTotal(computeTotal());

    /*
     * ============================================================
     * NEW RECORD
     *
     * IMPORTANT:
     * Do NOT trust the transaction number generated when the
     * New PO screen was opened.
     *
     * saveNewTransaction() gets a fresh number immediately
     * before saving.
     * ============================================================
     */
    if (pnEditMode == EditMode.ADDNEW) {
        return saveNewTransaction(loNewEnt);
    }

    /*
     * ============================================================
     * EXISTING RECORD / UPDATE
     *
     * Everything below remains the normal update process.
     * ============================================================
     */
    if (!pbWithParent) {
        poGRider.beginTrans();
    }

    // Load previous transaction
    loOldEnt = loadTransaction(poData.getTransNox());

    loNewEnt.setEntryNox(ItemCount());
    loNewEnt.setDateModified(poGRider.getServerDate());

    lbUpdate = saveDetail(loNewEnt.getTransNox());

    if (!lbUpdate) {
        lsSQL = "";
    } else {
        lsSQL = MiscUtil.makeSQL(
                (GEntity) loNewEnt,
                (GEntity) loOldEnt,
                "sTransNox = " + SQLUtil.toSQL(loNewEnt.getValue(1))
        );
    }

    if (!lsSQL.equals("") && getErrMsg().isEmpty()) {
        if (poGRider.executeQuery(
                lsSQL,
                loNewEnt.getTable(),
                "",
                ""
        ) == 0) {

            if (!poGRider.getErrMsg().isEmpty()) {
                setErrMsg(poGRider.getErrMsg());
            } else {
                setMessage("No record updated");
            }
        }

        lbUpdate = true;
    }

    if (!pbWithParent) {
        if (!getErrMsg().isEmpty()) {
            poGRider.rollbackTrans();
        } else {
            poGRider.commitTrans();
        }
    }

    return lbUpdate;
}

    public boolean saveTransaction1() {
        String lsSQL = "";
        boolean lbUpdate = false;

        UnitPOMaster loOldEnt = null;
        UnitPOMaster loNewEnt = null;
        UnitPOMaster loResult = null;

        // Check for the value of foEntity
        if (!(poData instanceof UnitPOMaster)) {
            setErrMsg("Invalid Entity Passed as Parameter");
            return false;
        }

        // Typecast the Entity to this object
        loNewEnt = (UnitPOMaster) poData;

        // Test if entry is ok
        if (loNewEnt.getBranchCd() == null || loNewEnt.getBranchCd().isEmpty()) {
            setMessage("No branch detected.");
            return false;
        }

        if (loNewEnt.getDateTransact() == null) {
            setMessage("No transact date detected.");
            return false;
        }

        if (loNewEnt.getCompanyID() == null || loNewEnt.getCompanyID().isEmpty()) {
            setMessage("No company detected.");
            return false;
        }

        if (loNewEnt.getDestinat() == null || loNewEnt.getDestinat().isEmpty()) {
            setMessage("No destination detected.");
            return false;
        }

        if (loNewEnt.getSupplier() == null || loNewEnt.getSupplier().isEmpty()) {
            setMessage("No supplier detected.");
            return false;
        }

        if (getDetail(ItemCount() - 1, "sStockIDx").equals("")) {
            deleteDetail(ItemCount() - 1);
        }

        if (ItemCount() <= 0) {
            setMessage("Unable to save no item record.");
            return false;
        }
        loNewEnt.setTranTotal(computeTotal());
        if (!pbWithParent) {
            poGRider.beginTrans();
        }

        //delete empty detail
        if (paDetail.get(ItemCount() - 1).getStockID().equals("")) {
            deleteDetail(ItemCount() - 1);
        }

        // Generate the SQL Statement
        if (pnEditMode == EditMode.ADDNEW) {
            Connection loConn = null;
            loConn = setConnection();

            String lsTransNox = MiscUtil.getNextCode(loNewEnt.getTable(), "sTransNox", true, loConn, psBranchCd);

            loNewEnt.setTransNox(lsTransNox);
            loNewEnt.setEntryNox(ItemCount());
            loNewEnt.setModifiedBy(poGRider.getUserID());
            loNewEnt.setDateModified(poGRider.getServerDate());

            if (!pbWithParent) {
                MiscUtil.close(loConn);
            }

            lbUpdate = saveDetail(loNewEnt.getTransNox());
            if (!lbUpdate) {
                lsSQL = "";
            } else {
                lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt);
            }
        } else {
            //Load previous transaction
            loOldEnt = loadTransaction(poData.getTransNox());

            loNewEnt.setEntryNox(ItemCount());
            loNewEnt.setDateModified(poGRider.getServerDate());

            lbUpdate = saveDetail(loNewEnt.getTransNox());
            if (!lbUpdate) {
                lsSQL = "";
            } else {
                lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt, (GEntity) loOldEnt, "sTransNox = " + SQLUtil.toSQL(loNewEnt.getValue(1)));
            }
        }

        if (!lsSQL.equals("") && getErrMsg().isEmpty()) {
            if (poGRider.executeQuery(lsSQL, loNewEnt.getTable(), "", "") == 0) {
                if (!poGRider.getErrMsg().isEmpty()) {
                    setErrMsg(poGRider.getErrMsg());
                } else {
                    setMessage("No record updated");
                }
            }
            lbUpdate = true;
        }

        if (!pbWithParent) {
            if (!getErrMsg().isEmpty()) {
                poGRider.rollbackTrans();
            } else {
                poGRider.commitTrans();
            }
        }

        return lbUpdate;
    }

    private boolean saveDetail(String fsTransNox) {
        setMessage("");
        int lnCtr;
        String lsSQL = "";
        UnitPODetail loNewEnt = null;

        for (lnCtr = 0; lnCtr <= paDetail.size() - 1; lnCtr++) {
            if (paDetail.isEmpty()) {
                setMessage("Unable to save empty detail transaction.");
                return false;
            } else if (paDetail.get(lnCtr).getStockID().equals("")
                    || paDetail.get(lnCtr).getQuantity().doubleValue() <= 0.00
                    || paDetail.get(lnCtr).getUnitPrice().doubleValue() <= 0.00) {
                setMessage("Detail might not have item or zero quantity or zero unit price.");
                return false;
            }
        }

        if (pnEditMode == EditMode.ADDNEW) {
            Connection loConn = null;
            loConn = setConnection();

            for (lnCtr = 0; lnCtr <= paDetail.size() - 1; lnCtr++) {
                loNewEnt = paDetail.get(lnCtr);

                if (!loNewEnt.getStockID().equals("")) {
                    loNewEnt.setTransNox(fsTransNox);
                    loNewEnt.setEntryNox(lnCtr + 1);
                    loNewEnt.setDateModified(poGRider.getServerDate());

                    lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt, "nQtyOnHnd;sBrandNme");

                    if (!lsSQL.equals("")) {
                        if (poGRider.executeQuery(lsSQL, loNewEnt.getTable(), "", "") == 0) {
                            if (!poGRider.getErrMsg().isEmpty()) {
                                setErrMsg(poGRider.getErrMsg());
                                return false;
                            }
                        }
                    }
                }
            }
        } else {
            ArrayList<UnitPODetail> laSubUnit = loadTransactionDetail(poData.getTransNox());

            for (lnCtr = 0; lnCtr <= paDetail.size() - 1; lnCtr++) {

                loNewEnt = paDetail.get(lnCtr);

                if (!loNewEnt.getStockID().equals("")) {
//                    System.out.println("paDetail = " + paDetail.size());
//                    System.out.println("laSubUnit = " + laSubUnit.size());
//                    if (lnCtr > laSubUnit.size()-1){
//                        loNewEnt.setTransNox(fsTransNox);
//                        loNewEnt.setEntryNox(lnCtr + 1);
//                        loNewEnt.setDateModified(poGRider.getServerDate());
//                        lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt,"nQtyOnHnd;sBrandNme");
//
//                    }else if (lnCtr == laSubUnit.size()-1){
//                        if (loNewEnt.getEntryNox() != lnCtr+1) loNewEnt.setEntryNox(lnCtr+1);
//                        if(loNewEnt.getTransNox().isEmpty())loNewEnt.setTransNox(fsTransNox);
//                        lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt, 
//                                                (GEntity) laSubUnit.get(lnCtr), 
//                                                " nEntryNox = " + SQLUtil.toSQL(loNewEnt.getValue(2)) + 
//                                                " AND sTransNox = " + SQLUtil.toSQL(fsTransNox),
//                                                "nQtyOnHnd;sBrandNme");
//
//                    }else  if (lnCtr < laSubUnit.size()-1){
//                        if (loNewEnt.getEntryNox() != lnCtr+1) loNewEnt.setEntryNox(lnCtr+1);
//                        if(loNewEnt.getTransNox().isEmpty())loNewEnt.setTransNox(fsTransNox);
//                        lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt, 
//                                                (GEntity) laSubUnit.get(lnCtr), 
//                                                " nEntryNox = " + SQLUtil.toSQL(loNewEnt.getValue(2)) + 
//                                                " AND sTransNox = " + SQLUtil.toSQL(fsTransNox),
//                                                "nQtyOnHnd;sBrandNme");
//                    }
                    if (lnCtr <= laSubUnit.size() - 1) {
                        if (loNewEnt.getEntryNox() != lnCtr + 1) {
                            loNewEnt.setEntryNox(lnCtr + 1);
                        }
                        if (loNewEnt.getTransNox().isEmpty()) {
                            loNewEnt.setTransNox(fsTransNox);
                        }
                        lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt,
                                (GEntity) laSubUnit.get(lnCtr),
                                " nEntryNox = " + SQLUtil.toSQL(loNewEnt.getValue(2))
                                + " AND sTransNox = " + SQLUtil.toSQL(loNewEnt.getValue(1)),
                                "nQtyOnHnd;sBrandNme");

                    } else {
                        loNewEnt.setTransNox(fsTransNox);
                        loNewEnt.setEntryNox(lnCtr + 1);
                        loNewEnt.setDateModified(poGRider.getServerDate());
                        lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt, "nQtyOnHnd;sBrandNme");
                    }

                    System.out.println("lsSQL = " + lsSQL);
                    if (!lsSQL.equals("")) {
                        if (poGRider.executeQuery(lsSQL, loNewEnt.getTable(), "", "") == 0) {
                            if (!poGRider.getErrMsg().isEmpty()) {
                                setErrMsg(poGRider.getErrMsg());
                                return false;
                            }
                        }
                    }
                } else {
                    for (int lnCtr2 = lnCtr; lnCtr2 <= laSubUnit.size() - 1; lnCtr2++) {
                        lsSQL = "DELETE FROM " + poDetail.getTable()
                                + " WHERE nEntryNox = " + SQLUtil.toSQL(laSubUnit.get(lnCtr2).getEntryNox())
                                + " AND sTransNox = " + SQLUtil.toSQL(laSubUnit.get(lnCtr2).getTransNox());

                        if (!lsSQL.equals("")) {
                            if (poGRider.executeQuery(lsSQL, poDetail.getTable(), "", "") == 0) {
                                if (!poGRider.getErrMsg().isEmpty()) {
                                    setErrMsg(poGRider.getErrMsg());
                                    return false;
                                }
                            }
                        }
                    }
                    break;
                }
            }
            if (lnCtr <= laSubUnit.size() - 1) {
                lsSQL = "DELETE FROM " + poDetail.getTable()
                        + " WHERE nEntryNox >= " + SQLUtil.toSQL(laSubUnit.get(lnCtr).getEntryNox())
                        + " AND sTransNox = " + SQLUtil.toSQL(laSubUnit.get(lnCtr).getTransNox());

                if (!lsSQL.equals("")) {
                    if (poGRider.executeQuery(lsSQL, poDetail.getTable(), "", "") == 0) {
                        if (!poGRider.getErrMsg().isEmpty()) {
                            setErrMsg(poGRider.getErrMsg());
                            return false;
                        }
                    }
                }
            }

        }
        return true;
    }

    public boolean deleteTransaction(String string) {
        UnitPOMaster loObject = loadTransaction(string);
        boolean lbResult = false;

        if (loObject == null) {
            setMessage("No record found...");
            return lbResult;
        }

        String lsSQL = "DELETE FROM " + loObject.getTable()
                + " WHERE sTransNox = " + SQLUtil.toSQL(string);

        if (!pbWithParent) {
            poGRider.beginTrans();
        }

        if (poGRider.executeQuery(lsSQL, loObject.getTable(), "", "") == 0) {
            if (!poGRider.getErrMsg().isEmpty()) {
                setErrMsg(poGRider.getErrMsg());
            } else {
                setErrMsg("No record deleted.");
            }
        } else {
            lbResult = true;
        }

        //delete detail rows
        lsSQL = "DELETE FROM " + poDetail.getTable()
                + " WHERE sTransNox = " + SQLUtil.toSQL(string);

        if (poGRider.executeQuery(lsSQL, poDetail.getTable(), "", "") == 0) {
            if (!poGRider.getErrMsg().isEmpty()) {
                setErrMsg(poGRider.getErrMsg());
            } else {
                setErrMsg("No record deleted.");
            }
        } else {
            lbResult = true;
        }

        if (!pbWithParent) {
            if (getErrMsg().isEmpty()) {
                poGRider.commitTrans();
            } else {
                poGRider.rollbackTrans();
            }
        }

        return lbResult;
    }

    public boolean closeTransaction1(String fsTransNox, String fsUserIDxx, String fsAprvCode) {
        UnitPOMaster loObject = loadTransaction(fsTransNox);
        boolean lbResult = false;
        if (pnEditMode != EditMode.READY) {
            return false;
        } else {
            if (loObject == null) {
                setMessage("No record found...");
                return lbResult;
            }

            if (fsAprvCode == null || fsAprvCode.isEmpty()) {
                setMessage("Invalid/No approval code detected.");
                return lbResult;
            }

            if (!loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_OPEN)) {
                setMessage("Unable to close closed/cancelled/posted/voided transaction.");
                return lbResult;
            }

            if (poGRider.getUserLevel() < UserRight.SUPERVISOR) {
                setMessage("User is not allowed confirming transaction.");
                return lbResult;
            }

            String lsSQL = "UPDATE " + loObject.getTable()
                    + " SET  cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_CLOSED)
                    + ", sApproved = " + SQLUtil.toSQL(fsUserIDxx)
                    + ", dApproved = " + SQLUtil.toSQL(poGRider.getServerDate())
                    + ", sAprvCode = " + SQLUtil.toSQL(fsAprvCode)
                    + ", sModified = " + SQLUtil.toSQL(psUserIDxx)
                    + ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate())
                    + " WHERE sTransNox = " + SQLUtil.toSQL(loObject.getTransNox());

            if (!pbWithParent) {
                poGRider.beginTrans();
            }

            if (poGRider.executeQuery(lsSQL, loObject.getTable(), "", "") == 0) {
                if (!poGRider.getErrMsg().isEmpty()) {
                    setErrMsg(poGRider.getErrMsg());
                } else {
                    setErrMsg("No record deleted.");
                }
            } else {
                lbResult = true;
            }

            if (!pbWithParent) {
                if (getErrMsg().isEmpty()) {
                    poGRider.commitTrans();
                } else {
                    poGRider.rollbackTrans();
                }
            }
        }

        return lbResult;
    }
    public boolean closeTransaction(String fsTransNox, String fsUserIDxx, String fsAprvCode) {

        System.out.println("CLOSE: Start");

        UnitPOMaster loObject = loadTransaction(fsTransNox);

        System.out.println("CLOSE: loadTransaction finished");

        boolean lbResult = false;

        if (pnEditMode != EditMode.READY) {
            System.out.println("CLOSE: Invalid edit mode = " + pnEditMode);
            return false;
        }

        if (loObject == null) {
            setMessage("No record found...");
            return false;
        }

        if (fsAprvCode == null || fsAprvCode.isEmpty()) {
            setMessage("Invalid/No approval code detected.");
            return false;
        }

        if (!loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_OPEN)) {
            setMessage("Unable to close closed/cancelled/posted/voided transaction.");
            return false;
        }

        if (poGRider.getUserLevel() < UserRight.SUPERVISOR) {
            setMessage("User is not allowed confirming transaction.");
            return false;
        }

        String lsSQL = "UPDATE " + loObject.getTable()
                + " SET cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_CLOSED)
                + ", sApproved = " + SQLUtil.toSQL(fsUserIDxx)
                + ", dApproved = " + SQLUtil.toSQL(poGRider.getServerDate())
                + ", sAprvCode = " + SQLUtil.toSQL(fsAprvCode)
                + ", sModified = " + SQLUtil.toSQL(psUserIDxx)
                + ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate())
                + " WHERE sTransNox = " + SQLUtil.toSQL(loObject.getTransNox());

        System.out.println("CLOSE: SQL = " + lsSQL);

        if (!pbWithParent) {
            System.out.println("CLOSE: Before beginTrans");
            poGRider.beginTrans();
            System.out.println("CLOSE: After beginTrans");
        }

        System.out.println("CLOSE: Before executeQuery");

        if (poGRider.executeQuery(
                lsSQL,
                loObject.getTable(),
                "",
                ""
        ) == 0) {

            System.out.println("CLOSE: executeQuery returned 0");

            if (!poGRider.getErrMsg().isEmpty()) {
                setErrMsg(poGRider.getErrMsg());
            } else {
                setErrMsg("No record updated.");
            }

        } else {

            System.out.println("CLOSE: executeQuery succeeded");

            lbResult = true;
        }

        if (!pbWithParent) {

            System.out.println("CLOSE: Before commit/rollback");

            if (!getErrMsg().isEmpty()) {
                poGRider.rollbackTrans();
                System.out.println("CLOSE: rollback complete");
            } else {
                poGRider.commitTrans();
                System.out.println("CLOSE: commit complete");
            }
        }

        System.out.println("CLOSE: Finished");

        return lbResult;
    }

    public boolean closeTransactionByPass(String fsTransNox) {
        UnitPOMaster loObject = loadTransaction(fsTransNox);
        boolean lbResult = false;
        if (pnEditMode != EditMode.READY) {
            return false;
        } else {
            if (loObject == null) {
                setMessage("No record found...");
                return lbResult;
            }

            if (!loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_OPEN)) {
                setMessage("Unable to close approved/cancelled/posted/voided transaction.");
                return lbResult;
            }

            if (!pbWithParent) {
                poGRider.beginTrans();
            }

            JSONObject loJSON = new JSONObject();
            StatusChange loStatus = new StatusChange();
            loStatus.setPbWithUI(true);
            loJSON = loStatus.statusChange(loObject.getTable(),
                    fsTransNox, "",
                    "1",
                    poGRider,
                    true);
            if ("success".equals((String) loJSON.get("result"))) {
                lbResult = true;
            } else {
                setErrMsg((String) loJSON.get("message"));
            }
            if (!pbWithParent) {
                if (getErrMsg().isEmpty()) {
                    poGRider.commitTrans();
                } else {
                    poGRider.rollbackTrans();
                }
            }
        }

        return lbResult;
    }

    public boolean postTransaction(String string) {
        if (poGRider.getUserLevel() <= UserRight.ENCODER) {
            JSONObject loJSON = showFXDialog.getApproval(poGRider);

            if (loJSON == null) {
                ShowMessageFX.Warning("Approval failed.", pxeModuleName, "Unable to post transaction");
            }

            if ((int) loJSON.get("nUserLevl") <= UserRight.ENCODER) {
                ShowMessageFX.Warning("User account has no right to approve.", pxeModuleName, "Unable to post transaction");
                return false;
            }
        }

        UnitPOMaster loObject = loadTransaction(string);
        boolean lbResult = false;

        if (loObject == null) {
            setMessage("No record found...");
            return lbResult;
        }

        if (loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_POSTED)
                || loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_CANCELLED)
                || loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_VOID)) {
            setMessage("Unable to close proccesed transaction.");
            return lbResult;
        }

        String lsSQL = "UPDATE " + loObject.getTable()
                + " SET  cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_POSTED)
                + ", sModified = " + SQLUtil.toSQL(psUserIDxx)
                + ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate())
                + " WHERE sTransNox = " + SQLUtil.toSQL(loObject.getTransNox());

        if (!pbWithParent) {
            poGRider.beginTrans();
        }

        if (poGRider.executeQuery(lsSQL, loObject.getTable(), "", "") == 0) {
            if (!poGRider.getErrMsg().isEmpty()) {
                setErrMsg(poGRider.getErrMsg());
            } else {
                setErrMsg("No record deleted.");
            }
        } else {
            lbResult = true;
        }

        if (!pbWithParent) {
            if (getErrMsg().isEmpty()) {
                poGRider.commitTrans();
            } else {
                poGRider.rollbackTrans();
            }
        }
        return lbResult;
    }

    public boolean voidTransaction(String string) {
        UnitPOMaster loObject = loadTransaction(string);
        boolean lbResult = false;

        if (loObject == null) {
            setMessage("No record found...");
            return lbResult;
        }

        if (loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_POSTED)
                || loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_CANCELLED)
                || loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_VOID)) {
            setMessage("Unable to close processed transaction.");
            return lbResult;
        }

        String lsSQL = "UPDATE " + loObject.getTable()
                + " SET  cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_VOID)
                + ", sModified = " + SQLUtil.toSQL(psUserIDxx)
                + ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate())
                + " WHERE sTransNox = " + SQLUtil.toSQL(loObject.getTransNox());

        if (!pbWithParent) {
            poGRider.beginTrans();
        }

        if (poGRider.executeQuery(lsSQL, loObject.getTable(), "", "") == 0) {
            if (!poGRider.getErrMsg().isEmpty()) {
                setErrMsg(poGRider.getErrMsg());
            } else {
                setErrMsg("No record deleted.");
            }
        } else {
            lbResult = true;
        }

        if (!pbWithParent) {
            if (getErrMsg().isEmpty()) {
                poGRider.commitTrans();
            } else {
                poGRider.rollbackTrans();
            }
        }
        return lbResult;
    }

    public boolean cancelTransaction(String string) {
        UnitPOMaster loObject = loadTransaction(string);
        boolean lbResult = false;

        if (loObject == null) {
            setMessage("No record found...");
            return lbResult;
        }

        if (loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_POSTED)
                || loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_CANCELLED)
                || loObject.getTranStat().equalsIgnoreCase(TransactionStatus.STATE_VOID)) {
            setMessage("Unable to close processed transaction.");
            return lbResult;
        }

        String lsSQL = "UPDATE " + loObject.getTable()
                + " SET  cTranStat = " + SQLUtil.toSQL(TransactionStatus.STATE_CANCELLED)
                + ", sModified = " + SQLUtil.toSQL(psUserIDxx)
                + ", dModified = " + SQLUtil.toSQL(poGRider.getServerDate())
                + " WHERE sTransNox = " + SQLUtil.toSQL(loObject.getTransNox());

        if (!pbWithParent) {
            poGRider.beginTrans();
        }

        if (poGRider.executeQuery(lsSQL, loObject.getTable(), "", "") == 0) {
            if (!poGRider.getErrMsg().isEmpty()) {
                setErrMsg(poGRider.getErrMsg());
            } else {
                setErrMsg("No record deleted.");
            }
        } else {
            lbResult = true;
        }

        if (!pbWithParent) {
            if (getErrMsg().isEmpty()) {
                poGRider.commitTrans();
            } else {
                poGRider.rollbackTrans();
            }
        }
        return lbResult;
    }

    public boolean SearchDetail(int fnRow, int fnCol, String fsValue, boolean fbSearch, boolean fbByCode) {
        String lsHeader = "";
        String lsColName = "";
        String lsColCrit = "";
        String lsSQL = "";
        String lsCondition = "";
        JSONObject loJSON;

        setErrMsg("");
        setMessage("");

        switch (fnCol) {

            case 3:

                lsSQL = MiscUtil.addCondition(getSQ_Stocks(), "a.cRecdStat = " + SQLUtil.toSQL(RecordStatus.ACTIVE));
                lsHeader = "Barcode»Description»Brand»Unit»Qty. on hand»Inv. Type";
                lsColName = "sBarCodex»sDescript»xBrandNme»sMeasurNm»nQtyOnHnd»xInvTypNm";
                lsColCrit = "a.sBarCodex»a.sDescript»b.sDescript»f.sMeasurNm»e.nQtyOnHnd»d.sDescript";
                if (ItemCount() > 0) {
                    for (int lnCtr = 0; lnCtr < ItemCount(); lnCtr++) {
                        lsCondition += ", " + SQLUtil.toSQL(getDetail(lnCtr, "sStockIDx"));
                    }
                    lsCondition = " AND a.sStockIDx NOT IN (" + lsCondition.substring(2) + ") GROUP BY a.sStockIDx";
                }
                if (!lsCondition.isEmpty()) {
                    lsSQL = lsSQL + lsCondition;
                }
                System.out.println(lsSQL);
                loJSON = showFXDialog.jsonSearch(poGRider,
                        lsSQL,
                        fsValue,
                        lsHeader,
                        lsColName,
                        lsColCrit,
                        fbSearch ? 0 : 1);

                if (loJSON != null) {
                    setDetail(fnRow, fnCol, (String) loJSON.get("sStockIDx"));
                    setDetail(fnRow, "nUnitPrce", Double.valueOf((String) loJSON.get("nUnitPrce")));
                    setDetail(fnRow, "nQtyOnHnd", Double.valueOf((String) loJSON.get("nQtyOnHnd")));
                    setDetail(fnRow, "sBrandNme", (String) loJSON.get("xBrandNme"));

                    paDetailOthers.get(fnRow).setValue("sStockIDx", (String) loJSON.get("sStockIDx"));
                    paDetailOthers.get(fnRow).setValue("xBarCodex", (String) loJSON.get("sBarCodex"));
                    paDetailOthers.get(fnRow).setValue("xDescript", (String) loJSON.get("sDescript"));
                    paDetailOthers.get(fnRow).setValue("xQtyOnHnd", Double.valueOf((String) loJSON.get("nQtyOnHnd")));
                    paDetailOthers.get(fnRow).setValue("nQtyOnHnd", Double.valueOf((String) loJSON.get("nQtyOnHnd")));
                    paDetailOthers.get(fnRow).setValue("sMeasurNm", (String) loJSON.get("sMeasurNm"));

                    return true;
                } else {
//                    setDetail(fnRow, fnCol, "");
//                    setDetail(fnRow, "sBrandNme", "");
//                    setDetail(fnRow, "nUnitPrce", 0);
//                    setDetail(fnRow, "nQtyOnHnd", 0);
//                    
//                    paDetailOthers.get(fnRow).setValue("sStockIDx", "");
//                    paDetailOthers.get(fnRow).setValue("xBarCodex", "");
//                    paDetailOthers.get(fnRow).setValue("xDescript", "");
//                    paDetailOthers.get(fnRow).setValue("xQtyOnHnd", 0);
//                    paDetailOthers.get(fnRow).setValue("nQtyOnHnd", 0);
//                    paDetailOthers.get(fnRow).setValue("sMeasurNm", "");
                    return false;
                }
            default:
                return false;
        }
    }

    public boolean SearchDetail(int fnRow, String fsCol, String fsValue, boolean fbSearch, boolean fbByCode) {
        return SearchDetail(fnRow, poDetail.getColumn(fsCol), fsValue, fbSearch, fbByCode);
    }

    public void setMaster(int fnCol, Object foData) {
        if (pnEditMode != EditMode.UNKNOWN) {
            // Don't allow specific fields to assign values
            if (!(fnCol == poData.getColumn("sTransNox")
                    || fnCol == poData.getColumn("nEntryNox")
                    || fnCol == poData.getColumn("cTranStat")
                    || fnCol == poData.getColumn("sModified")
                    || fnCol == poData.getColumn("dModified"))) {

                poData.setValue(fnCol, foData);
                MasterRetreived(fnCol);
            }
        }
    }

    public void setMaster(String fsCol, Object foData) {
        setMaster(poData.getColumn(fsCol), foData);
    }

    public Object getMaster(int fnCol) {
        if (pnEditMode == EditMode.UNKNOWN) {
            return null;
        } else {
            return poData.getValue(fnCol);
        }
    }

    public Object getMaster(String fsCol) {
        return getMaster(poData.getColumn(fsCol));
    }

    public String getMessage() {
        return psWarnMsg;
    }

    public void setMessage(String string) {
        psWarnMsg = string;
    }

    public String getErrMsg() {
        return psErrMsgx;
    }

    public void setErrMsg(String string) {
        psErrMsgx = string;
    }

    public void setBranch(String string) {
        psBranchCd = string;
    }

    public void setWithParent(boolean bln) {
        pbWithParent = bln;
    }

    public String getSQ_Master() {
        return MiscUtil.makeSelect(new UnitPOMaster());
    }

    private String getSQ_Detail() {
        return "SELECT"
                + "  a.sTransNox"
                + ", a.nEntryNox"
                + ", a.sStockIDx"
                + ", a.nQuantity"
                + ", a.nUnitPrce"
                + ", a.nReceived"
                + ", a.nCancelld"
                + ", a.dModified"
                + ", c.sBarCodex"
                + ", c.sDescript"
                + ", b.nQtyOnHnd nQtyOnHnd"
                + ", d.sMeasurNm"
                + ", e.sDescript sBrandNme"
                + " FROM PO_Detail a"
                + ", Inv_Master b"
                + " LEFT JOIN Inventory c"
                + " ON b.sStockIDx = c.sStockIDx"
                + " LEFT JOIN Brand e"
                + " ON c.sBrandCde = e.sBrandCde"
                + " LEFT JOIN Measure d"
                + " ON c.sMeasurID = d.sMeasurID"
                + " WHERE a.sStockIDx = b.sStockIDx"
                + " AND b.sBranchCD = " + SQLUtil.toSQL(poGRider.getBranchCode());
    }

    public int ItemCount() {
        return paDetail.size();
    }

    private Connection setConnection() {
        Connection foConn;

        if (pbWithParent) {
            foConn = (Connection) poGRider.getConnection();
            if (foConn == null) {
                foConn = (Connection) poGRider.doConnect();
            }
        } else {
            foConn = (Connection) poGRider.doConnect();
        }

        return foConn;
    }

    public int getEditMode() {
        return pnEditMode;
    }

    private String getSQL_POMaster() {
        String lsTranStat = String.valueOf(pnTranStat);
        String lsCondition = "";
        if (lsTranStat.length() == 1) {
            lsCondition = "a.cTranStat = " + SQLUtil.toSQL(lsTranStat);
        } else {
            for (int lnCtr = 0; lnCtr <= lsTranStat.length() - 1; lnCtr++) {
                lsCondition = lsCondition + SQLUtil.toSQL(String.valueOf(lsTranStat.charAt(lnCtr))) + ",";
            }
            lsCondition = "(" + lsCondition.substring(0, lsCondition.length() - 1) + ")";
            lsCondition = "a.cTranStat IN " + lsCondition;
        }

        return MiscUtil.addCondition("SELECT "
                + "  a.sTransNox"
                + ", a.sBranchCd"
                + ", DATE_FORMAT(a.dTransact, '%m/%d/%Y') AS dTransact"
                + ", a.sInvTypCd"
                + ", a.nTranTotl"
                + ", b.sBranchNm"
                + ", c.sDescript"
                + ", d.sClientNm"
                + ", a.cTranStat"
                + ", a.sReferNox"
                + ", CASE "
                + " WHEN a.cTranStat = '0' THEN 'OPEN'"
                + " WHEN a.cTranStat = '1' THEN 'CLOSED'"
                + " WHEN a.cTranStat = '2' THEN 'POSTED'"
                + " WHEN a.cTranStat = '3' THEN 'CANCELLED'"
                + " WHEN a.cTranStat = '4' THEN 'VOID'"
                + " END AS xTranStat"
                + " FROM PO_Master a"
                + " LEFT JOIN Branch b"
                + " ON a.sBranchCd = b.sBranchCd"
                + " LEFT JOIN Inv_Type c"
                + " ON a.sInvTypCd = c.sInvTypCd"
                + ", Client_Master d"
                + " WHERE a.sSupplier = d.sClientID"
                + " AND LEFT(a.sTransNox, 4) = " + SQLUtil.toSQL(poGRider.getBranchCode()), lsCondition);
    }

    private String getSQ_POMaster() {
        String lsTranStat = String.valueOf(pnTranStat);
        String lsCondition = "";
        String lsSQL = "SELECT"
                + "  sTransNox"
                + ", sBranchCd"
                + ", dTransact"
                + ", sCompnyID"
                + ", sDestinat"
                + ", sSupplier"
                + ", sReferNox"
                + ", sTermCode"
                + ", nTranTotl"
                + ", sRemarksx"
                + ", sSourceNo"
                + ", sSourceCd"
                + ", cEmailSnt"
                + ", nEmailSnt"
                + ", nEntryNox"
                + ", sInvTypCd"
                + ", cTranStat"
                + ", sPrepared"
                + ", dPrepared"
                + ", sApproved"
                + ", dApproved"
                + ", sAprvCode"
                + ", sPostedxx"
                + ", dPostedxx"
                + ", sModified"
                + ", dModified"
                + " FROM PO_Master a"
                + " WHERE sTransNox LIKE " + SQLUtil.toSQL(psBranchCd + "%");

        if (lsTranStat.length() == 1) {
            lsCondition = "cTranStat = " + SQLUtil.toSQL(lsTranStat);
        } else {
            for (int lnCtr = 0; lnCtr <= lsTranStat.length() - 1; lnCtr++) {
                lsCondition = lsCondition + SQLUtil.toSQL(String.valueOf(lsTranStat.charAt(lnCtr))) + ",";
            }
            lsCondition = "(" + lsCondition.substring(0, lsCondition.length() - 1) + ")";
            lsCondition = "cTranStat IN " + lsCondition;
        }

        lsSQL = MiscUtil.addCondition(lsSQL, lsCondition);
        return lsSQL;
    }

    private String getSQ_Stocks() {
        String lsSQL = "SELECT "
                + "  a.sStockIDx"
                + ", a.sBarCodex"
                + ", a.sDescript"
                + ", a.sBriefDsc"
                + ", a.sAltBarCd"
                + ", a.sCategCd1"
                + ", a.sCategCd2"
                + ", a.sCategCd3"
                + ", a.sCategCd4"
                + ", a.sBrandCde"
                + ", a.sModelCde"
                + ", a.sColorCde"
                + ", a.sInvTypCd"
                + ", a.nUnitPrce"
                + ", a.nSelPrice"
                + ", a.nDiscLev1"
                + ", a.nDiscLev2"
                + ", a.nDiscLev3"
                + ", a.nDealrDsc"
                + ", a.cComboInv"
                + ", a.cWthPromo"
                + ", a.cSerialze"
                + ", a.cUnitType"
                + ", a.cInvStatx"
                + ", a.sSupersed"
                + ", a.cRecdStat"
                + ", b.sDescript xBrandNme"
                + ", c.sDescript xModelNme"
                + ", d.sDescript xInvTypNm"
                + ", f.sMeasurNm"
                + ", IFNULL(e.nQtyOnHnd,0) nQtyOnHnd"
                + " FROM Inventory a"
                + " LEFT JOIN Brand b"
                + " ON a.sBrandCde = b.sBrandCde"
                + " LEFT JOIN Model c"
                + " ON a.sModelCde = c.sModelCde"
                + " LEFT JOIN Inv_Type d"
                + " ON a.sInvTypCd = d.sInvTypCd"
                + " LEFT JOIN Measure f"
                + " ON a.sMeasurID = f.sMeasurID"
                + ", Inv_Master e"
                + " WHERE a.sStockIDx = e.sStockIDx"
                + " AND e.sBranchCd = " + SQLUtil.toSQL(psBranchCd);
        if (!System.getProperty("store.inventory.type").isEmpty()) {
            lsSQL = MiscUtil.addCondition(lsSQL, "a.sInvTypCd IN " + CommonUtils.getParameter(System.getProperty("store.inventory.type")));
        }

        return lsSQL;
    }

    public void printColumnsMaster() {
        poData.list();
    }

    public void printColumnsDetail() {
        poDetail.list();
    }

    public void setTranStat(int fnValue) {
        this.pnTranStat = fnValue;
    }

    //callback methods
    public void setCallBack(IMasterDetail foCallBack) {
        poCallBack = foCallBack;
    }

    private void MasterRetreived(int fnRow) {
        if (poCallBack == null) {
            return;
        }

        poCallBack.MasterRetreive(fnRow);
    }

    private void DetailRetreived(int fnRow) {
        if (poCallBack == null) {
            return;
        }

        poCallBack.DetailRetreive(fnRow);
    }

    public void setClientNm(String fsClientNm) {
        this.psClientNm = fsClientNm;
    }

    public Inventory GetInventory(String fsValue, boolean fbByCode, boolean fbSearch) {
        Inventory instance = new Inventory(poGRider, psBranchCd, fbSearch);
        instance.BrowseRecord(fsValue, fbByCode, false);
        return instance;
    }

    public XMTerm GetTerm(String fsValue, boolean fbByCode) {
        if (fbByCode && fsValue.equals("")) {
            return null;
        }

        XMTerm instance = new XMTerm(poGRider, psBranchCd, true);
        if (instance.browseRecord(fsValue, fbByCode)) {
            return instance;
        } else {
            return null;
        }
    }

    public XMBranch GetBranch(String fsValue, boolean fbByCode) {
        if (fbByCode && fsValue.equals("")) {
            return null;
        }

        XMBranch instance = new XMBranch(poGRider, psBranchCd, true);
        if (instance.browseRecord(fsValue, fbByCode)) {
            return instance;
        } else {
            return null;
        }
    }

    public JSONObject GetSupplier(String fsValue, boolean fbByCode) {
        if (fbByCode && fsValue.equals("")) {
            return null;
        }

        XMClient instance = new XMClient(poGRider, psBranchCd, true);
        return instance.SearchClient(fsValue, fbByCode);
    }

    public XMInventoryType GetInventoryType(String fsValue, boolean fbByCode) {
        if (fbByCode && fsValue.equals("")) {
            return null;
        }

        XMInventoryType instance = new XMInventoryType(poGRider, psBranchCd, true);
        if (instance.browseRecord(fsValue, fbByCode)) {
            return instance;
        } else {
            return null;
        }
    }

    public boolean SearchMaster(int fnCol, String fsValue, boolean fbByCode) {
        switch (fnCol) {
            case 2: //sBranchCd
                XMBranch loBranch = new XMBranch(poGRider, psBranchCd, true);
                if (loBranch.browseRecord(fsValue, fbByCode)) {
                    setMaster(fnCol, loBranch.getMaster("sBranchCd"));
                    setMaster("sCompnyID", loBranch.getMaster("sCompnyID"));
                    MasterRetreived(fnCol);
                    return true;
                }
                break;
            case 5: //sDestinat
                XMBranch loDest = new XMBranch(poGRider, psBranchCd, true);
                if (loDest.browseRecord(fsValue, fbByCode)) {
                    setMaster(fnCol, loDest.getMaster("sBranchCd"));
                    MasterRetreived(fnCol);
                    return true;
                }
                break;
            case 6: //sSupplier
                XMSupplier loSupplier = new XMSupplier(poGRider, psBranchCd, true);
                if (loSupplier.browseRecord(fsValue, psBranchCd, fbByCode)) {
                    setMaster(fnCol, loSupplier.getMaster("sClientID"));
                    MasterRetreived(fnCol);
                    return true;
                }
                break;
            case 8: //sTermCode               
                XMTerm loTerm = new XMTerm(poGRider, psBranchCd, true);
                if (loTerm.browseRecord(fsValue, fbByCode)) {
                    setMaster(fnCol, loTerm.getMaster("sTermCode"));
                    MasterRetreived(fnCol);
                    return true;
                }
                break;
            case 16: //sInvTypCd
                XMInventoryType loInv = new XMInventoryType(poGRider, psBranchCd, true);
                if (loInv.browseRecord(fsValue, fbByCode)) {
                    setMaster(fnCol, loInv.getMaster("sInvTypCd"));
                    MasterRetreived(fnCol);
                    return true;
                }
                break;
        }

        return false;
    }

    public boolean SearchMaster(String fsCol, String fsValue, boolean fbByCode) {
        return SearchMaster(poData.getColumn(fsCol), fsValue, fbByCode);
    }

    public void ShowMessageFX() {
        if (!getErrMsg().isEmpty()) {
            if (!getMessage().isEmpty()) {
                ShowMessageFX.Error(getErrMsg(), pxeModuleName, getMessage());
            } else {
                ShowMessageFX.Error(getErrMsg(), pxeModuleName, null);
            }
        } else {
            if (!getMessage().isEmpty()) {
                ShowMessageFX.Information(null, pxeModuleName, getMessage());
            }
        }
    }
    
    
        private String getDestinat(String sBranchCd) {
            String lsSQL = "SELECT sBranchCd, sBranchNm FROM Branch WHERE sBranchCd = " + SQLUtil.toSQL(sBranchCd);

            try (ResultSet rs = poGRider.executeQuery(lsSQL)) {
                if (rs.next()) {
                    return rs.getString("sBranchNm");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "";
        }
    
        private String takeTerm(String sTermCode) {
            String lsSQL = "SELECT sTermCode, sDescript FROM Term WHERE sTermCode = " + SQLUtil.toSQL(sTermCode);

            try (ResultSet rs = poGRider.executeQuery(lsSQL)) {
                if (rs.next()) {
                    return rs.getString("sDescript");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "";
        }
        

//Helpers 
        private boolean isDuplicateKeyError(String fsErrMsg) {
            return fsErrMsg != null && (
                    fsErrMsg.contains("Duplicate entry")
                    || fsErrMsg.contains("1062")
                    || fsErrMsg.toLowerCase().contains("duplicate key")
            );
        }

        private boolean isTransNoxSaved(String fsTransNox) {
            String lsSQL = "SELECT sTransNox FROM " + poData.getTable()
                         + " WHERE sTransNox = " + SQLUtil.toSQL(fsTransNox)
                         + " LIMIT 1";

            try (ResultSet loRS = poGRider.executeQuery(lsSQL)) {
                return loRS.next();
            } catch (SQLException ex) {
                setErrMsg(ex.getMessage());
                return true;
            }
        }

        private String getFreshTransNox() {
            Connection loConn = null;

            try {
                loConn = setConnection();

                return MiscUtil.getNextCode(
                        poData.getTable(),
                        "sTransNox",
                        true,
                        loConn,
                        psBranchCd
                );

            } finally {
                if (!pbWithParent) {
                    MiscUtil.close(loConn);
                }
            }
        }

        private void rebaseDetailTransNox(String fsTransNox) {
            for (int lnCtr = 0; lnCtr < paDetail.size(); lnCtr++) {
                paDetail.get(lnCtr).setTransNox(fsTransNox);
                paDetail.get(lnCtr).setEntryNox(lnCtr + 1);
            }
        }

        private boolean saveNewTransaction(UnitPOMaster loNewEnt) {

            /*
             * Retry several times in case another PC saves a transaction
             * between our number generation and our actual INSERT.
             */
            for (int lnTry = 0; lnTry < 10; lnTry++) {

                if (!pbWithParent) {
                    poGRider.beginTrans();
                }

                /*
                 * ============================================================
                 * GET THE CURRENT TRANSACTION NUMBER
                 *
                 * Do NOT use the transaction number generated when the
                 * New PO screen was opened.
                 *
                 * Get it again immediately before saving.
                 * ============================================================
                 */
                String lsTransNox = getFreshTransNox();

                if (lsTransNox == null || lsTransNox.isEmpty()) {

                    if (!pbWithParent) {
                        poGRider.rollbackTrans();
                    }

                    setMessage("Unable to generate transaction number.");
                    return false;
                }

                /*
                 * ============================================================
                 * UPDATE TRANSACTION NUMBER
                 * ============================================================
                 */
                loNewEnt.setTransNox(lsTransNox);

                /*
                 * ============================================================
                 * UPDATE REFERENCE NUMBER
                 *
                 * Reference No. is NOT automatically changed when we change
                 * sTransNox because sReferNox is a separate database field.
                 *
                 * Your system uses the LAST 8 CHARACTERS of the transaction
                 * number as the Reference No.
                 *
                 * Example:
                 *
                 * POW126002536
                 *     ↓
                 * 26002536
                 * ============================================================
                 */
                String lsReferNox = lsTransNox;

                if (lsReferNox.length() > 8) {
                    lsReferNox = lsReferNox.substring(lsReferNox.length() - 8);
                }

                loNewEnt.setReferNo(lsReferNox);

                /*
                 * ============================================================
                 * UPDATE DETAILS
                 *
                 * Keep all the user's existing detail information.
                 * Only change the transaction number attached to them.
                 * ============================================================
                 */
                rebaseDetailTransNox(lsTransNox);

                loNewEnt.setEntryNox(ItemCount());
                loNewEnt.setModifiedBy(poGRider.getUserID());
                loNewEnt.setDateModified(poGRider.getServerDate());

                /*
                 * ============================================================
                 * SAVE DETAILS
                 * ============================================================
                 */
                if (!saveDetail(lsTransNox)) {

                    if (!pbWithParent) {
                        poGRider.rollbackTrans();
                    }

                    return false;
                }

                /*
                 * ============================================================
                 * GENERATE INSERT SQL FOR MASTER
                 * ============================================================
                 */
                String lsSQL = MiscUtil.makeSQL((GEntity) loNewEnt);

                /*
                 * ============================================================
                 * SAVE MASTER
                 * ============================================================
                 */
                if (poGRider.executeQuery(
                        lsSQL,
                        loNewEnt.getTable(),
                        "",
                        ""
                ) > 0) {

                    /*
                     * SUCCESS
                     */
                    if (!pbWithParent) {
                        poGRider.commitTrans();
                    }

                    return true;
                }

                /*
                 * Something went wrong.
                 */
                String lsErr = poGRider.getErrMsg();

                /*
                 * Roll back this attempt before retrying.
                 */
                if (!pbWithParent) {
                    poGRider.rollbackTrans();
                }

                /*
                 * ============================================================
                 * DUPLICATE TRANSACTION NUMBER
                 *
                 * Another PC saved this number first.
                 *
                 * Try again with another fresh transaction number.
                 * ============================================================
                 */
                if (isDuplicateKeyError(lsErr)) {

                    setErrMsg("");

                    continue;
                }

                /*
                 * It wasn't a duplicate-key problem.
                 * Stop and report the actual error.
                 */
                if (lsErr != null && !lsErr.isEmpty()) {
                    setErrMsg(lsErr);
                }

                return false;
            }

            setMessage("Unable to generate a unique transaction number.");
            return false;
        }
        
//End of Helpers 
    public boolean printRecord() {
        if (poData == null) {
            ShowMessageFX.Warning("Unable to print transaction.", "Warning", "No record loaded.");
            return false;
        }

        //Create the parameter
        Map<String, Object> params = new HashMap<>();
        params.put("sCompnyNm", "Guanzon Group");
        params.put("sBranchNm", poGRider.getBranchName());
        params.put("sAddressx", poGRider.getAddress() + ", " + poGRider.getTownName() + " " + poGRider.getProvince());
        params.put("sDestinat", getDestinat(poData.getDestinat()));
        params.put("sTransNox", poData.getTransNox());
        params.put("sReferNox", poData.getReferNo());
        params.put("dTransact", SQLUtil.dateFormat(poData.getDateTransact(), SQLUtil.FORMAT_LONG_DATE));
        params.put("sPrintdBy", poGRider.getClientName());
        params.put("sTermName", takeTerm(poData.getTermCode()));
        JSONObject loJSON;
        
        
        try {
            String lsSQL = "SELECT sClientNm FROM Client_Master WHERE sClientID = " + SQLUtil.toSQL(poData.getSupplier());
            ResultSet loRS = poGRider.executeQuery(lsSQL);

            if (loRS.next()) {
                params.put("xSupplier", loRS.getString("sClientNm"));
            } else {
                params.put("xSupplier", "NOT SPECIFIED");
            }

            params.put("sApprval1", "-");
            params.put("sApprval2", "-");
            params.put("sApprval3", "-");

            if (poData.getApprovalCode().equals("USERAPPROVAL")) {
                lsSQL = "SELECT"
                        + "  a.sTransNox"
                        + ", c.sClientNm"
                        + " FROM PO_Master a"
                        + " LEFT JOIN xxxSysUser b"
                        + " ON a.sApproved = b.sUserIDxx"
                        + " LEFT JOIN Client_Master c"
                        + " ON b.sEmployNo = c.sClientID"
                        + " WHERE a.cTranStat = '1'"
                        + " AND a.sAprvCode = 'USERAPPROVAL'"
                        + " AND a.sTransNox = " + SQLUtil.toSQL(poData.getTransNox())
                        + " ORDER BY a.dApproved";

                loRS = poGRider.executeQuery(lsSQL);
                
                while (loRS.next()) {
                    params.put("sApprval1", loRS.getString("sTransNox") + " - " + loRS.getString("sClientNm"));
                }
            } else {
                lsSQL = "SELECT"
                        + "  a.sTransNox"
                        + ", b.sClientNm"
                        + " FROM Tokenized_Approval_Request a"
                        + " LEFT JOIN Client_Master b"
                        + " ON a.sReqstdTo = b.sClientID"
                        + " WHERE a.cTranStat = '1'"
                        + " AND a.sSourceCd = 'PO'"
                        + " AND a.sRqstType = 'LP'"
                        + " AND a.sSourceNo = " + SQLUtil.toSQL(poData.getTransNox())
                        + " ORDER BY a.dApproved"
                        + " LIMIT 3";

                loRS = poGRider.executeQuery(lsSQL);

                try {
                    int lnCtr = 0;
                    while (loRS.next()) {
                        switch (lnCtr) {
                            case 0:
                                params.put("sApprval1", loRS.getString("sTransNox") + " - " + loRS.getString("sClientNm"));
                                break;
                            case 1:
                                params.put("sApprval2", loRS.getString("sTransNox") + " - " + loRS.getString("sClientNm"));
                                break;
                            default:
                                params.put("sApprval3", loRS.getString("sTransNox") + " - " + loRS.getString("sClientNm"));
                                break;
                        }
                        lnCtr++;
                    }
                } catch (SQLException ex) {
                    ex.printStackTrace();
                    return false;
                }
            }

//            lsSQL = "SELECT sClientNm FROM Client_Master WHERE sClientID IN ("
//                    + "SELECT sEmployNo FROM xxxSysUser WHERE sUserIDxx = " + SQLUtil.toSQL(poData.getApprovedBy().isEmpty() ? poData.getPreparedBy() : poData.getApprovedBy()) + ")";
//            loRS = poGRider.executeQuery(lsSQL);
//
//            if (loRS.next()) {
//                params.put("sApprval1", loRS.getString("sClientNm"));
//            } else {
//                params.put("sApprval1", "");
//            }
//            
//            
//            params.put("sApprval2", "");
//            lsSQL = "SELECT sClientNm FROM Client_Master WHERE sClientID IN (" +
//                        "SELECT sEmployNo FROM xxxSysUser WHERE sUserIDxx = " + SQLUtil.toSQL(poData.getApprovedBy()) + ")";
//            loRS = poGRider.executeQuery(lsSQL);
//            
//            if (loRS.next()){
//                params.put("sApprval2", loRS.getString("sClientNm"));
//            } else {
//                params.put("sApprval2", "");
//            }
            params.put("xRemarksx", poData.getRemarks());

            JSONArray loArray = new JSONArray();

            String lsBarCodex;
            String lsDescript;
            String lsMeasurex;
            Inventory loInventory = new Inventory(poGRider, psBranchCd, true);

            for (int lnCtr = 0; lnCtr <= ItemCount() - 1; lnCtr++) {
                loInventory.BrowseRecord((String) getDetail(lnCtr, "sStockIDx"), true, false);
                lsBarCodex = (String) loInventory.getMaster("sBarCodex");
                lsDescript = (String) loInventory.getMaster("sDescript");
                lsMeasurex = (String) loInventory.getMeasureMent(loInventory.getMaster("sMeasurID").toString());

                loJSON = new JSONObject();
                loJSON.put("sField01", lsBarCodex);
                loJSON.put("sField02", lsDescript);
                loJSON.put("sField03", lsMeasurex);
                loJSON.put("sField04", getDetail(lnCtr, "sBrandNme"));
                loJSON.put("nField01", getDetail(lnCtr, "nQuantity"));
                loJSON.put("nField02", getDetail(lnCtr, "nUnitPrce"));
                loJSON.put("nField03", (Double.parseDouble(getDetail(lnCtr, "nUnitPrce").toString()) * Double.parseDouble(getDetail(lnCtr, "nQuantity").toString())));
                loArray.add(loJSON);
                System.out.println("nQuantity = " + getDetail(lnCtr, "nQuantity"));
            }

            InputStream stream = new ByteArrayInputStream(loArray.toJSONString().getBytes("UTF-8"));
            JsonDataSource jrjson;

            jrjson = new JsonDataSource(stream);

            JasperPrint jrprint = JasperFillManager.fillReport(poGRider.getReportPath()
                    + "PurchaseOrderLP.jasper", params, jrjson);
            System.out.println("HERE: " + poGRider.getReportPath());
            JasperViewer jv = new JasperViewer(jrprint, false);
            jv.setVisible(true);
            jv.setAlwaysOnTop(true);
        } catch (JRException | UnsupportedEncodingException | SQLException ex) {
            Logger.getLogger(PurchaseOrders.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }

        return true;
    }
    //Member Variables
    private GRider poGRider = null;
    private String psUserIDxx = "";
    private String psBranchCd = "";
    private String psWarnMsg = "";
    private String psErrMsgx = "";
    private String psClientNm = "";
    private boolean pbWithParent = false;
    private int pnEditMode;
    private int pnTranStat = 0;
    private IMasterDetail poCallBack;

    private UnitPOMaster poData = new UnitPOMaster();
    private UnitPODetail poDetail = new UnitPODetail();
    private ArrayList<UnitPODetail> paDetail;
    private ArrayList<UnitPOReceivingDetailOthers> paDetailOthers;

    private final String pxeModuleName = PurchaseOrders.class
            .getSimpleName();
}
