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

public class InstrumentFileExtractor implements ResultSetExtractor<List<InstrumentFile>> {

    private static ReglissList listRef(Long id) {
        ReglissList list = new ReglissList();
        list.setId(id);
        return list;
    }

    private static com.bnpp.regliss.entity.Version versionRef(Long id) {
        com.bnpp.regliss.entity.Version v = new com.bnpp.regliss.entity.Version();
        v.setId(id);
        return v;
    }

    @Override
    public List<InstrumentFile> extractData(ResultSet rs) throws SQLException, DataAccessException {

        Map<Long, InstrumentFile> instrumentMap = new LinkedHashMap<>();
        while (rs.next()) {
            Long instrId = rs.getLong("i_id");
            InstrumentFile instrument = instrumentMap.get(instrId);
            if (instrument == null) {
                instrument = new InstrumentFile();
                instrument.setId(instrId);
                instrument.setList(listRef(rs.getLong("i_list_id")));
                instrument.setVersion(versionRef(rs.getLong("i_version_id")));
                try {
                    instrument.setRecordOrigin(rs.getString("i_record_origin"));
                    instrument.setOlsYes(rs.getString("i_ols_yes"));
                    instrument.setOlsNo(rs.getString("i_ols_no"));
                    instrument.setLinkEntity(rs.getString("i_link_entity"));
                    instrument.setLinkCsid(rs.getString("i_link_csid"));
                    instrument.setSanctionedParentEntity(rs.getString("i_sanctioned_parent_entity"));
                    instrument.setNameDirectIssuer(rs.getString("i_name_direct_issuer"));
                    instrument.setConfidenceLevel(rs.getString("i_confidence_level"));
                    instrument.setIsin(rs.getString("i_isin"));
                    instrument.setInstrName(rs.getString("i_instr_name"));
                    instrument.setFisn(rs.getString("i_fisn"));
                    instrument.setChValor(rs.getString("i_ch_valor"));
                    instrument.setIndicativeIssueDate(rs.getString("i_indicative_issue_date"));
                    instrument.setInstrumentType(rs.getString("i_instrument_type"));
                    instrument.setSanctionsRelevantAssetClass(rs.getString("i_sanction_relevant_asset_class"));
                    instrument.setMainInstrument(rs.getString("i_main_instrument"));
                    instrument.setEquityTypeOfIssuance(rs.getString("i_equity_type_of_issuance"));
                    instrument.setDenominationCurrency(rs.getString("i_denomination_currency"));
                    instrument.setMaturityDate(rs.getString("i_maturity_date"));
                    instrument.setDebtLifetimeInDays(rs.getString("i_debt_lifetime_in_days"));
                    instrument.setActiveFlag(rs.getString("i_active_flag"));
                    instrument.setDateOpenedInSix(rs.getString("i_date_opened_in_six"));
                    instrument.setSubstituteIssueDate(rs.getString("i_substitute_issue_date"));
                    instrument.setIssueDate(rs.getString("i_issue_date"));
                    instrument.setCapitalChangeDate(rs.getString("i_capital_change_date"));
                    instrument.setSedol(rs.getString("i_sedol"));
                    instrument.setCusip(rs.getString("i_cusip"));
                    instrument.setCins(rs.getString("i_cins"));
                    instrument.setAustrian(rs.getString("i_austrian"));
                    instrument.setBelgian(rs.getString("i_belgian"));
                    instrument.setCanadian(rs.getString("i_canadian"));
                    instrument.setGerman(rs.getString("i_german"));
                    instrument.setDenmark(rs.getString("i_denmark"));
                    instrument.setFranceRga(rs.getString("i_france_rga"));
                    instrument.setFranceEuroclear(rs.getString("i_france_euroclear"));
                    instrument.setItalian(rs.getString("i_italian"));
                    instrument.setJapaneseCurrent(rs.getString("i_japanese_current"));
                    instrument.setJapaneseNew(rs.getString("i_japanese_new"));
                    instrument.setLuxembourg(rs.getString("i_luxembourg"));
                    instrument.setNetherland(rs.getString("i_netherland"));
                    instrument.setNorwegian(rs.getString("i_norwegian"));
                    instrument.setSwedish(rs.getString("i_swedish"));
                    instrument.setXsIntNumber(rs.getString("i_xs_int_number"));
                    instrument.setPortugal(rs.getString("i_portugal"));
                    instrument.setSouthKorea(rs.getString("i_south_korea"));
                    instrument.setHongKong(rs.getString("i_hong_kong"));
                    instrument.setFigiGlobalId(rs.getString("i_figi_global_id"));
                    instrument.setFigiGlobalShareClassLevelId(rs.getString("i_figi_global_share_class_id"));
                    instrument.setObligorRole(rs.getString("i_obligor_role"));
                    instrument.setUnderlyingObligor(rs.getString("i_underlying_obligor"));
                    instrument.setUnderlyingObligorGk(rs.getString("i_underlying_obligor_gk"));
                    instrument.setReferenceCapital(rs.getString("i_reference_capital"));
                    instrument.setActualCapital(rs.getString("i_actual_capital"));
                    instrument.setRegimes(rs.getString("i_regimes"));
                } catch (SQLException e) {
                    throw new DataRetrievalFailureException("Failed to retrieve instrument file data from result set", e);
                }

                instrument.setSixTargets(new ArrayList<>());
                instrumentMap.put(instrId, instrument);
            }

            Long targetId = rs.getLong("t_id");
            if (!rs.wasNull()) {
                SixTarget target = new SixTarget();
                target.setId(targetId);
                target.setSixInstruId(instrument);
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
                    throw new DataRetrievalFailureException("Failed to retrieve six target data from result set", e);
                }

                instrument.getSixTargets().add(target);
            }
        }

        return new ArrayList<>(instrumentMap.values());
    }
}
