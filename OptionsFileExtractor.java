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

public class OptionsFileExtractor implements ResultSetExtractor<List<OptionsFile>> {

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
    public List<OptionsFile> extractData(ResultSet rs) throws SQLException, DataAccessException {

        Map<Long, OptionsFile> map = new LinkedHashMap<>();
        while (rs.next()) {
            Long optId = rs.getLong("i_id");
            OptionsFile opt = map.get(optId);
            if (opt == null) {
                opt = new OptionsFile();
                opt.setId(optId);
                opt.setList(listRef(rs.getLong("i_list_id")));
                opt.setVersion(versionRef(rs.getLong("i_version_id")));
                try {
                    opt.setChOption(rs.getString("i_ch_option"));
                    opt.setIsinOption(rs.getString("i_isin_option"));
                    opt.setDescription(rs.getString("i_description"));
                    opt.setFisn(rs.getString("i_fisn"));
                    opt.setIssuerGk(rs.getString("i_issuer_gk"));
                    opt.setIssuerName(rs.getString("i_issuer_name"));
                    opt.setDateOpenedInSix(rs.getString("i_date_opened_in_six"));
                    opt.setExpiryDate(rs.getString("i_expiry_date"));
                    opt.setInstrumentType(rs.getString("i_instrument_type"));
                    opt.setDenominationCurrency(rs.getString("i_denomination_currency"));
                    opt.setActiveFlag(rs.getString("i_active_flag"));
                    opt.setIssueDate(rs.getString("i_issue_date"));
                    opt.setUnderlyingCh(rs.getString("i_underlying_ch"));
                    opt.setUnderlyingIsin(rs.getString("i_underlying_isin"));
                    opt.setUnderlyingIssuerGk(rs.getString("i_underlying_issuer_gk"));
                    opt.setUnderlyingIssuerName(rs.getString("i_underlying_issuer_name"));
                    opt.setSedol(rs.getString("i_sedol"));
                    opt.setCusip(rs.getString("i_cusip"));
                    opt.setCins(rs.getString("i_cins"));
                    opt.setAustrian(rs.getString("i_austrian"));
                    opt.setBelgian(rs.getString("i_belgian"));
                    opt.setCanadian(rs.getString("i_canadian"));
                    opt.setGerman(rs.getString("i_german"));
                    opt.setDenmark(rs.getString("i_denmark"));
                    opt.setFranceRga(rs.getString("i_france_rga"));
                    opt.setFranceEuroclear(rs.getString("i_france_euroclear"));
                    opt.setItalian(rs.getString("i_italian"));
                    opt.setJapaneseCurrent(rs.getString("i_japanese_current"));
                    opt.setJapaneseNew(rs.getString("i_japanese_new"));
                    opt.setLuxembourg(rs.getString("i_luxembourg"));
                    opt.setNetherland(rs.getString("i_netherland"));
                    opt.setNorwegian(rs.getString("i_norwegian"));
                    opt.setSwedish(rs.getString("i_swedish"));
                    opt.setXsIntNumber(rs.getString("i_xs_int_number"));
                    opt.setPortugal(rs.getString("i_portugal"));
                    opt.setSouthKorea(rs.getString("i_south_korea"));
                    opt.setHongKong(rs.getString("i_hong_kong"));
                    opt.setFigiGlobalId(rs.getString("i_figi_global_id"));
                    opt.setFigiGlobalShareClassLevelId(rs.getString("i_figi_global_share_class_id"));
                    opt.setRegimes(rs.getString("i_regimes"));
                    opt.setConfidenceLevel(rs.getString("i_confidence_level"));
                } catch (SQLException e) {
                    throw new DataRetrievalFailureException("Failed to retrieve options file data from result set", e);
                }

                opt.setSixTargets(new ArrayList<>());
                map.put(optId, opt);
            }

            Long targetId = rs.getLong("t_id");
            if (!rs.wasNull()) {
                SixTarget target = new SixTarget();
                target.setId(targetId);
                target.setSixOptId(opt);

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

                opt.getSixTargets().add(target);
            }
        }

        return new ArrayList<>(map.values());
    }
}
