 -----------------------------------------------------------------
  -- H2 does not natively support TO_NUMBER, REGEXP_SUBSTR functions (what the heck?????)
  -- so we must use ALIAS feature to implement it ("Fine. I'll do it myself!")
  -----------------------------------------------------------------
CREATE ALIAS IF NOT EXISTS TO_NUMBER AS $$
Long toNumber(String value) {
  return value == null ? null : Long.valueOf(value);
}
$$;

CREATE ALIAS IF NOT EXISTS REGEXP_SUBSTR AS $$
String regexpSubstr(String value, String pattern, int start, int iteration, String matchParameter, int subExpression ) {
  return value.split("@@@")[iteration-1].trim();
}
$$;

