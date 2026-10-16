package com.gameShows.live.jeopardy;

public record BoardCellView(String cellId, String category, int value, String state) {

    public static BoardCellView from(BoardCell cell) {
        return new BoardCellView(cell.getCellId(), cell.getCategory(), cell.getValue(), cell.getState().name());
    }
}
