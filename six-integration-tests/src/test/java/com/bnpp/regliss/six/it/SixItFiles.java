package com.bnpp.regliss.six.it;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test only: small synthetic SIX files in the format the project's XSLTs read
 * (instruments-xslt.xml: SANCTIONS/INSTRUMENTS/INSTRUMENT, structured-xslt.xml: SANCTIONS_STRUCTURED_PRODUCTS/...).
 */
final class SixItFiles {

    static final String PREFIX = "GSM_2_203_EU-RU-UK-US_";

    private SixItFiles() {
    }

    /** GSM_2_203_EU-RU-UK-US_INSTR_FULL_20261020_143113.xml (kind = INSTR / STRUCT, delivery = yyyyMMdd_HHmmss). */
    static String fileName(String kind, String delivery) {
        return PREFIX + kind + "_FULL_" + delivery + ".xml";
    }

    /** One SIX target of an instrument: its name and its regimes ({regime, legal basis, OLS Yes/No}). */
    static final class Target {
        final String name;
        final List<String[]> regimes = new ArrayList<>();

        Target(String name, String regime, String legalBasis) {
            this.name = name;
            regimes.add(new String[]{regime, legalBasis, "Yes"});
        }

        Target regime(String regime, String legalBasis) {
            regimes.add(new String[]{regime, legalBasis, "Yes"});
            return this;
        }
    }

    /** One instrument (or structured product): its identifiers, confidence level and targets. */
    static final class Instrument {
        final String ch;
        final String isin;
        final String confidence;
        final String underlyingCh;
        final List<Target> targets;

        private Instrument(String ch, String isin, String confidence, String underlyingCh, List<Target> targets) {
            this.ch = ch;
            this.isin = isin;
            this.confidence = confidence;
            this.underlyingCh = underlyingCh;
            this.targets = targets;
        }
    }

    static Instrument instrument(String ch, String isin, String confidence, Target... targets) {
        return new Instrument(ch, isin, confidence, null, Arrays.asList(targets));
    }

    /** Structured product of an underlying instrument (its confidence level comes from the instrument file). */
    static Instrument structured(String ch, String isin, String underlyingCh, Target... targets) {
        return new Instrument(ch, isin, null, underlyingCh, Arrays.asList(targets));
    }

    static Target target(String name, String regime, String legalBasis) {
        return new Target(name, regime, legalBasis);
    }

    static String instrumentFile(Instrument... instruments) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<SANCTIONS><INSTRUMENTS>");
        for (Instrument i : instruments) {
            xml.append("<INSTRUMENT>")
                    .append(element("RECORD_ORIGIN", "SIX"))
                    .append(element("NAME_DIRECT_ISSUER", "Issuer of " + i.isin))
                    .append(element("CONFIDENCE_LEVEL", i.confidence))
                    .append("<IDENTIFIERS>").append(identifier("CH", i.ch)).append(identifier("ISIN", i.isin)).append("</IDENTIFIERS>")
                    .append(element("INSTR_NAME", "Bond " + i.isin))
                    .append(element("INSTRUMENT_TYPE", "Bond"))
                    .append(element("ACTIVE_FLAG", "Y"))
                    .append(element("ISSUE_DATE", "20200115"))
                    .append(element("MATURITY_DATE", "20300115"));
            targets(xml, i.targets);
            xml.append("</INSTRUMENT>");
        }
        return xml.append("</INSTRUMENTS></SANCTIONS>").toString();
    }

    static String structuredFile(Instrument... products) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<SANCTIONS_STRUCTURED_PRODUCTS><INSTRUMENTS>");
        for (Instrument p : products) {
            xml.append("<INSTRUMENT>")
                    .append("<HOST>").append(identifier("CH", p.ch)).append(identifier("ISIN", p.isin)).append("</HOST>")
                    .append("<UNDERLYING>").append(identifier("CH", p.underlyingCh)).append("</UNDERLYING>")
                    .append(element("DESCRIPTION", "Product on " + p.underlyingCh))
                    .append(element("INSTRUMENT_TYPE", "Structured product"))
                    .append(element("ACTIVE_FLAG", "Y"))
                    .append(element("ISSUE_DATE", "20210301"));
            targets(xml, p.targets);
            xml.append("</INSTRUMENT>");
        }
        return xml.append("</INSTRUMENTS></SANCTIONS_STRUCTURED_PRODUCTS>").toString();
    }


    private static void targets(StringBuilder xml, List<Target> targets) {
        xml.append("<TARGETS>");
        for (Target t : targets) {
            xml.append("<TARGET NAME=\"").append(t.name).append("\"><REGIMES>");
            for (String[] r : t.regimes) {
                xml.append("<REGIME NAME=\"").append(r[0]).append("\">")
                        .append(element("LEGAL_BASIS", r[1])).append(element("OLS", r[2])).append("</REGIME>");
            }
            xml.append("</REGIMES>")
                    .append(element("SANCTIONED", "Yes"))
                    .append(element("SANCTIONS_RATIONALE", "Rationale of " + t.name))
                    .append(element("STATUS", "Active"))
                    .append(element("SANCTION_FLAG_CHANGED", "No"))
                    .append("</TARGET>");
        }
        xml.append("</TARGETS>");
    }

    private static String identifier(String type, String value) {
        return "<IDENTIFIER TYPE=\"" + type + "\">" + element("VALUE", value) + "</IDENTIFIER>";
    }

    private static String element(String name, String value) {
        return value == null ? "" : "<" + name + ">" + value + "</" + name + ">";
    }
}
