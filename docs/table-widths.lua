-- Pandoc filter used only for the PDF build (see pdf.yaml).
-- GitHub-flavoured Markdown tables carry no column widths, so the PDF engine
-- would give every column the same width. This sets each column's width in
-- proportion to how much text it holds, and left-aligns every column.

local function cell_length(cell)
  return #pandoc.utils.stringify(cell.contents)
end

function Table(tbl)
  local ncols = #tbl.colspecs
  local totals = {}
  for i = 1, ncols do totals[i] = 0 end
  for _, body in ipairs(tbl.bodies) do
    for _, row in ipairs(body.body) do
      for i, cell in ipairs(row.cells) do
        totals[i] = totals[i] + cell_length(cell)
      end
    end
  end
  -- Square roots keep short columns from becoming too narrow.
  local weights, sum = {}, 0
  for i = 1, ncols do
    weights[i] = math.sqrt(totals[i] + 20)
    sum = sum + weights[i]
  end
  for i = 1, ncols do
    tbl.colspecs[i] = {pandoc.AlignLeft, weights[i] / sum}
  end
  return tbl
end
