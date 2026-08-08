from lost_found_agent.matching import rank_candidates, score_candidate


def test_missing_fields_are_removed_from_weight_denominator() -> None:
    score, reasons = score_candidate(
        {"category": "UMBRELLA"},
        {"category": "UMBRELLA"},
        "en",
    )

    assert score == 1.0
    assert reasons == ["Same item category"]


def test_threshold_filters_candidates_and_result_is_limited_to_five() -> None:
    query = {"category": "BAG", "colour": "blue"}
    candidates = [
        {
            "id": index,
            "itemName": f"Bag {index}",
            "category": "BAG",
            "description": "A bag",
            "colour": "Blue",
            "location": "Library",
            "eventDate": "2026-08-08",
            "status": "OPEN",
        }
        for index in range(10)
    ]
    candidates.append(
        {
            "id": 99,
            "itemName": "Wallet",
            "category": "WALLET_PURSE",
            "description": "Brown wallet",
            "colour": "Brown",
            "location": "Gym",
            "eventDate": "2026-01-01",
            "status": "OPEN",
        }
    )

    results = rank_candidates(query, candidates, 0.35, "en")

    assert len(results) == 5
    assert all(result.category == "BAG" for result in results)
