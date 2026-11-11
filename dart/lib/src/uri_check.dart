// The one place a URI is judged before the SDK trusts its scheme or host.
//
// In Dart the check and the request read the host once, from one `Uri`, so
// there is no parser differential here (kahf-connect#16 found one in the
// Kotlin SDK, where Ktor sends the request). The authority rule is the same
// in both SDKs anyway, so the shared fixtures give the same verdicts: a URL
// one SDK refuses, the other refuses.

/// The scheme and `//` that open an authority, and the authority itself (up
/// to `/`, `?`, `#`).
final _authorityOf = RegExp(r'^[A-Za-z][A-Za-z0-9+.-]*://([^/?#]*)');

/// A strict RFC 3986 authority: at most one `@`, userinfo of unreserved,
/// sub-delim, `:` or %XX characters, a host of letters, digits and `-._~` (an
/// IDN arrives as punycode; a percent-escaped host is refused) or a bracketed
/// IPv6 literal, and a numeric port. A backslash, a space or a non-ASCII
/// character is in none of these.
final _strictAuthority = RegExp(
  r"^(?:(?:[A-Za-z0-9\-._~!$&'()*+,;=:]|%[0-9A-Fa-f]{2})*@)?"
  r'(?:[A-Za-z0-9\-._~]+|\[[0-9A-Fa-f:.]+\])(?::[0-9]*)?$',
);

/// [raw] as a `Uri` when it is absolute, has a host, and its authority is
/// strict RFC 3986; otherwise null.
Uri? checkedUri(String raw) {
  final authority = _authorityOf.firstMatch(raw)?.group(1);
  if (authority == null || !_strictAuthority.hasMatch(authority)) return null;
  final u = Uri.tryParse(raw);
  if (u == null || !u.hasScheme || u.host.isEmpty) return null;
  return u;
}
