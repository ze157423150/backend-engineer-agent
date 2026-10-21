"""Full-catalog control: maximal tool recall without selection savings."""


def select(state, catalog, k):
    return [tool["function"]["name"] for tool in catalog]
