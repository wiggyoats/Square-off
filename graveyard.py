"""Type- and colour-aware parking bank for captured pieces.

Confirmed on hardware: there are two mirrored parking banks just off the board
edges, with slots labelled by piece type.

  Right bank : x=8 (inner), x=9 (outer)
  Left  bank : x=-1 (inner), x=-2 (outer)

On the right bank the rank (y) is fixed per piece type on both columns:
  pawns  y=0,1,2,3   knight y=4   bishop y=5   rook y=6   queen y=7
  (two queen slots per bank -> one is the spare for a promotion).
The left bank is its 180-degree mirror (y -> 7-y), see slots_for().

Captures group by colour (black -> right bank, white -> left bank; set by
`black_bank`). Both the motor (alloc) and hand captures (alloc_human) put a piece
in the labelled slot for its type, so they agree with each other and the board.
"""

from __future__ import annotations

import chess

# ranks used by each piece type (kings are never captured)
_TYPE_RANKS = {
    chess.PAWN:   (0, 1, 2, 3),
    chess.KNIGHT: (4,),
    chess.BISHOP: (5,),
    chess.ROOK:   (6,),
    chess.QUEEN:  (7,),
}
_RIGHT_COLS = (8, 9)     # inner, outer
_LEFT_COLS = (-1, -2)


def bank_columns(color: bool, black_bank: str = "right"):
    """Return the two slot columns (inner, outer) for a piece of `color`."""
    black_cols = _RIGHT_COLS if black_bank == "right" else _LEFT_COLS
    white_cols = _LEFT_COLS if black_bank == "right" else _RIGHT_COLS
    return black_cols if color == chess.BLACK else white_cols


def slots_for(piece: chess.Piece, black_bank: str = "right"):
    """All slots a given piece could occupy, inner column first.

    The two banks are point-symmetric (a 180° mirror of each other): the right
    bank sits at x=8,9 with pawns near y=0..3, while the left bank at x=-1,-2 is
    its mirror image, so its ranks run the other way (y -> 7-y). Without this
    flip, white pieces (which use the left bank by default) get routed to the
    wrong slot even though black parks correctly on the right.
    """
    cols = bank_columns(piece.color, black_bank)
    ranks = _TYPE_RANKS.get(piece.piece_type, (7,))
    if cols[0] < 0:  # left bank -> mirror the rank layout
        ranks = tuple(7 - r for r in ranks)
    return [(c, r) for c in cols for r in ranks]


class Graveyard:
    """Tracks which parking slots are occupied by which captured piece."""

    def __init__(self, black_bank: str = "right"):
        self.black_bank = black_bank
        self.occupied = {}            # (sx, sy) -> chess.Piece

    def alloc(self, piece: chess.Piece):
        """Reserve and return a free labelled slot (sx, sy) for `piece`.

        Uses the slots labelled for the piece's type on its colour's bank. If
        those are all full (e.g. a third knight after a promotion), falls back to
        any free slot on the same bank rather than stacking two pieces in one.
        """
        typed = slots_for(piece, self.black_bank)
        for slot in typed + self._bank_slots(piece.color):
            if slot not in self.occupied:
                self.occupied[slot] = piece
                return slot
        # Bank completely full - reuse the last labelled slot.
        slot = typed[-1]
        self.occupied[slot] = piece
        return slot

    def alloc_human(self, piece: chess.Piece):
        """Slot we ASSUME a human used when taking `piece` off by hand.

        Identical to alloc(): the human drops the piece in the slot labelled for
        its type. Kept as a named method so call sites read clearly.
        """
        return self.alloc(piece)

    def _bank_slots(self, color: bool):
        """Every slot on `color`'s bank, inner column first."""
        cols = bank_columns(color, self.black_bank)
        return [(c, y) for c in cols for y in range(8)]

    def free(self, slot):
        self.occupied.pop(slot, None)

    def take(self, piece: chess.Piece):
        """Find and release a slot holding a matching piece; return it or None."""
        for slot, pc in list(self.occupied.items()):
            if pc == piece:
                del self.occupied[slot]
                return slot
        return None

    def __len__(self):
        return len(self.occupied)

