Rule: an amount far outside this account's own history, or above the hard limit.

- First establish the channel and whether the counterparty is new (getCounterpartyHistory).
- Card payment at home, at a merchant the customer already uses, followed by their usual activity:
  usually legitimate. Size alone is not fraud.
- TRANSFER far larger than anything the account has sent before, to a payee it has never paid: the
  classic shape of an authorised push payment scam or account takeover. Normal spending afterwards does
  not reduce the risk for transfers (victims carry on as usual). At least NEEDS_REVIEW unless the evidence
  explains it, e.g. an established payee the customer has paid similar amounts before.
- Account-takeover signals before a transfer (contact details changed, a new device, a raised transfer
  limit, in the days or hours before) make an unexplained transfer LIKELY_FRAUD.
- A payee on the inter-bank mule account list is a strong fraud signal on its own.
- Their absence clears nothing. In an authorised push payment scam the real customer sends the money from
  their own phone, persuaded by the scammer: no takeover signals, and a fresh mule account is not listed
  yet. Takeover signals and watchlist hits raise the risk when present; they do not lower it when absent.
- Typical actions for a suspicious transfer: contact the customer through a trusted channel, attempt
  recall of funds, consider holding further transfers to the payee.
- Regulations: for a suspicious transfer, search who bears the loss (Shared Responsibility Framework) and
  whether a suspicious transaction report is required (MAS Notice 626).
