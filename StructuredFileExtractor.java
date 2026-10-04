package com.bnpp.regliss.importer.six.extractor;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StructuredFileExtractor implements ResultSetExtractor<List<StructuredFile>> {

    private static ReglissList listRef(Long id) {
        ReglissList list = new ReglissList();
        list.setId(id);
        return list;
    }

    private static Version versionRef(Long id) {
        Version v = new Version();
        v.setId(id);
        return v;
    }

    @Override
    public List<StructuredFile> extractData(ResultSet rs) throws SQLException, DataAccessException {
        Map<Long, StructuredFile> map = new LinkedHashMap<>();

        while (rs.next()) {
            Long optId = rs.getLong("i_id");
            StructuredFile newStruct = map.get(optId);
            if (newStruct == null) {
                newStruct = new StructuredFile();
                newStruct.setId(optId);
                newStruct.setList(listRef(rs.getLong("i_list_id")));
                newStruct.setVersion(versionRef(rs.getLong("i_version_id")));
                try {
                    newStruct.setHostCh(rs.getString("i_host_ch"));
                    newStruct.setHostIsin(rs.getString("i_host_isin"));
                    newStruct.setHostGk(rs.getString("i_host_gk"));
                    newStruct.setHostIssuerShortname(rs.getString("i_host_issuer_shortname"));
                    newStruct.setDescription(rs.getString("i_description"));
                    newStruct.setFisn(rs.getString("i_fisn"));
                    newStruct.setDateOpenedInSix(rs.getString("i_date_opened_in_six"));
                    newStruct.setSubstituteIssueDate(rs.getString("i_substitute_issue_date"));
                    newStruct.setIndicativeIssueDate(rs.getString("i_indicative_issue_date"));
                    newStruct.setSettlementStyle(rs.getString("i_settlement_style"));
                    newStruct.setInstrumentType(rs.getString("i_instrument_type"));
                    newStruct.setDenominationCurrency(rs.getString("i_denomination_currency"));
                    newStruct.setMaturityDate(rs.getString("i_maturity_date"));
                    newStruct.setActiveFlag(rs.getString("i_active_flag"));
                    newStruct.setIssueDate(rs.getString("i_issue_date"));
                    newStruct.setUnderlyingCh(rs.getString("i_underlying_ch"));
                    newStruct.setUnderlyingIsin(rs.getString("i_underlying_isin"));
                    newStruct.setUnderlyingGk(rs.getString("i_underlying_gk"));
                    newStruct.setUnderlyingIssuerShortname(rs.getString("i_underlying_issuer_shortname"));
                    newStruct.setSedol(rs.getString("i_sedol"));
                    newStruct.setCusip(rs.getString("i_cusip"));
                    newStruct.setCins(rs.getString("i_cins"));
                    newStruct.setAustrian(rs.getString("i_austrian"));
                    newStruct.setBelgian(rs.getString("i_belgian"));
                    newStruct.setCanadian(rs.getString("i_canadian"));
                    newStruct.setGerman(rs.getString("i_german"));
                    newStruct.setDenmark(rs.getString("i_denmark"));
                    newStruct.setFranceRga(rs.getString("i_france_rga"));
                    newStruct.setFranceEuroClear(rs.getString("i_france_euroclear"));
                    newStruct.setItalian(rs.getString("i_italian"));
                    newStruct.setJapaneseCurrent(rs.getString("i_japanese_current"));
                    newStruct.setJapaneseNew(rs.getString("i_japanese_new"));
                    newStruct.setLuxembourg(rs.getString("i_luxembourg"));
                    newStruct.setNetherland(rs.getString("i_netherland"));
                    newStruct.setNorwegian(rs.getString("i_norwegian"));
                    newStruct.setSwedish(rs.getString("i_swedish"));
                    newStruct.setXsIntNumber(rs.getString("i_xs_int_number"));
                    newStruct.setPortugal(rs.getString("i_portugal"));
                    newStruct.setSouthKorea(rs.getString("i_south_korea"));
                    newStruct.setHongKong(rs.getString("i_hong_kong"));
                    newStruct.setFigiGlobalId(rs.getString("i_figi_global_id"));
                    newStruct.setFigiGlobalShareClassLevelId(rs.getString("i_figi_global_share_class_id"));
                    newStruct.setRegimes(rs.getString("i_regimes"));
                    newStruct.setConfidenceLevel(rs.getString("i_confidence_level"));
                } catch (SQLException e) {
                    throw new DataRetrievalFailureException("Failed to retrieve data from result set", e);
                }

                newStruct.setSixTargets(new ArrayList<>());
                map.put(optId, newStruct);
            }

            Long targetId = rs.getLong("t_id");
            if (!rs.wasNull()) {
                SixTarget target = new SixTarget();
                target.setId(targetId);
                target.setSixStructId(newStruct);
                try {
                    target.setTarget(rs.getString("t_target"));
                    target.setRegime(rs.getString("t_regime"));
                    target.setLegalBasis(rs.getString("t_legal_basis"));
                    target.setSanctioned(rs.getString("t_sanctioned"));
                    target.setStatus(rs.getString("t_status"));
                    target.setSanctionFlagChanged(rs.getString("t_sanction_flag_changed"));
                    target.setReasonForChange(rs.getString("t_reason_for_change"));
                    target.setSanctionsRationale(rs.getString("t_sanctions_rationale"));
                } catch (SQLException e) {
                    throw new DataRetrievalFailureException("Failed to retrieve data from result set", e);
                }

                newStruct.getSixTargets().add(target);
            }
        }

        return new ArrayList<>(map.values());
    }
}
