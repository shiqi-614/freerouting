package app.freerouting.board;

import app.freerouting.autoroute.ExpansionDrill;
import app.freerouting.autoroute.ItemAutorouteInfo;
import app.freerouting.boardgraphics.GraphicsContext;
import app.freerouting.core.Padstack;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Shape;
import app.freerouting.geometry.planar.*;
import app.freerouting.logger.FRLogger;
import app.freerouting.management.TextManager;

import java.awt.*;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.Iterator;
import java.util.Locale;

/**
 * Class describing the functionality of an electrical Item on the board, which may have a shape on
 * several layer, whose geometry is described by a padstack.
 */
public class Via extends DrillItem implements Serializable
{
  /**
   * True, if coppersharing of this via with smd pins of the same net is allowed.
   */
  public final boolean attach_allowed;
  private Padstack padstack;
  private transient Shape[] precalculated_shapes;
  /**
   * Temporary data used in the autoroute algorithm.
   */
  private transient ExpansionDrill autoroute_drill_info;

  /**
   * Creates a new instance of Via with the input parameters
   */
  public Via(Padstack p_padstack, Point p_center, int[] p_net_no_arr, int p_clearance_type, int p_id_no, int p_group_no, FixedState p_fixed_state, boolean p_attach_allowed, BasicBoard p_board)
  {
    super(p_center, p_net_no_arr, p_clearance_type, p_id_no, p_group_no, p_fixed_state, p_board);
    this.padstack = p_padstack;
    this.attach_allowed = p_attach_allowed;
  }

  @Override
  public Item copy(int p_id_no)
  {
    return new Via(padstack, get_center(), net_no_arr, clearance_class_no(), p_id_no, get_component_no(), get_fixed_state(), attach_allowed, board);
  }

  @Override
  public Shape get_shape(int p_index)
  {
    if (padstack == null)
    {
      FRLogger.warn("Via.get_shape: padstack is null");
      return null;
    }
    if (this.precalculated_shapes == null)
    {
      this.precalculated_shapes = new Shape[padstack.to_layer() - padstack.from_layer() + 1];
      for (int i = 0; i < this.precalculated_shapes.length; ++i)
      {
        int padstack_layer = i + this.first_layer();
        Vector translate_vector = get_center().difference_by(Point.ZERO);
        Shape curr_shape = padstack.get_shape(padstack_layer);

        if (curr_shape == null)
        {
          this.precalculated_shapes[i] = null;
        }
        else
        {
          this.precalculated_shapes[i] = (Shape) curr_shape.translate_by(translate_vector);
        }
      }
    }
    return this.precalculated_shapes[p_index];
  }

  @Override
  public Padstack get_padstack()
  {
    return padstack;
  }

  public void set_padstack(Padstack p_padstack)
  {
    padstack = p_padstack;
  }

  @Override
  public boolean is_routable()
  {
    return !is_user_fixed() && (this.net_count() > 0);
  }

  @Override
  public boolean is_obstacle(Item p_other)
  {
    if (p_other == this || p_other instanceof ComponentObstacleArea)
    {
      return false;
    }
    if ((p_other instanceof ConductionArea) && !((ConductionArea) p_other).get_is_obstacle())
    {
      return false;
    }
    if (!p_other.shares_net(this))
    {
      return true;
    }
    if (p_other instanceof Trace)
    {
      return false;
    }
    return !this.attach_allowed || !(p_other instanceof Pin) || !((Pin) p_other).drill_allowed();
  }

  /**
   * Checks, if the Via has contacts on at most 1 layer.
   */
  @Override
  public boolean is_tail()
  {
    Collection<Item> contact_list = this.get_normal_contacts();
    if (contact_list.size() <= 1)
    {
      return true;
    }
    Iterator<Item> it = contact_list.iterator();
    Item curr_contact_item = it.next();
    int first_contact_first_layer = curr_contact_item.first_layer();
    int first_contact_last_layer = curr_contact_item.last_layer();
    while (it.hasNext())
    {
      curr_contact_item = it.next();
      if (curr_contact_item.first_layer() != first_contact_first_layer || curr_contact_item.last_layer() != first_contact_last_layer)
      {
        return false;
      }
    }
    return true;
  }

  @Override
  public void change_placement_side(IntPoint p_pole)
  {
    if (this.board == null)
    {
      return;
    }
    Padstack new_padstack = this.board.library.get_mirrored_via_padstack(this.padstack);
    if (new_padstack == null)
    {
      return;
    }
    this.padstack = new_padstack;
    super.change_placement_side(p_pole);
    clear_derived_data();
  }

  public ExpansionDrill get_autoroute_drill_info(ShapeSearchTree p_autoroute_tree)
  {
    if (this.autoroute_drill_info == null)
    {
      ItemAutorouteInfo via_autoroute_info = this.get_autoroute_info();
      TileShape curr_drill_shape = TileShape.get_instance(this.get_center());
      this.autoroute_drill_info = new ExpansionDrill(curr_drill_shape, this.get_center(), this.first_layer(), this.last_layer());
      int via_layer_count = this.last_layer() - this.first_layer() + 1;
      for (int i = 0; i < via_layer_count; ++i)
      {
        this.autoroute_drill_info.room_arr[i] = via_autoroute_info.get_expansion_room(i, p_autoroute_tree);
      }
    }
    return this.autoroute_drill_info;
  }

  @Override
  public void clear_derived_data()
  {
    super.clear_derived_data();
    this.precalculated_shapes = null;
    this.autoroute_drill_info = null;
  }

  @Override
  public void clear_autoroute_info()
  {
    super.clear_autoroute_info();
    this.autoroute_drill_info = null;
  }

  @Override
  public boolean is_selected_by_filter(ItemSelectionFilter p_filter)
  {
    if (!this.is_selected_by_fixed_filter(p_filter))
    {
      return false;
    }
    return p_filter.is_selected(ItemSelectionFilter.SelectableChoices.VIAS);
  }

  @Override
  public Color[] get_draw_colors(GraphicsContext p_graphics_context)
  {
    Color[] result;
    if (this.net_count() == 0)
    {
      // display unconnected vias as obstacles
      result = p_graphics_context.get_obstacle_colors();

    }
    else if (this.first_layer() >= this.last_layer())
    {
      // display vias with only one layer as pins
      result = p_graphics_context.get_pin_colors();
    }
    else
    {
      result = p_graphics_context.get_via_colors(this.is_user_fixed());
    }
    return result;
  }

  @Override
  public double get_draw_intensity(GraphicsContext p_graphics_context)
  {
    double result;
    if (this.net_count() == 0)
    {
      // display unconnected vias as obstacles
      result = p_graphics_context.get_obstacle_color_intensity();

    }
    else if (this.first_layer() >= this.last_layer())
    {
      // display vias with only one layer as pins
      result = p_graphics_context.get_pin_color_intensity();
    }
    else
    {
      result = p_graphics_context.get_via_color_intensity();
    }
    return result;
  }

  @Override
  public void print_info(ObjectInfoPanel p_window, Locale p_locale)
  {
    TextManager tm = new TextManager(this.getClass(), p_locale);

    p_window.append_bold(tm.getText("via"));
    p_window.append(" " + tm.getText("at") + " ");
    p_window.append(this.get_center().to_float());
    p_window.append(", " + tm.getText("padstack"));
    p_window.append(padstack.name, tm.getText("padstack_info"), padstack);
    this.print_connectable_item_info(p_window, p_locale);
    p_window.newline();
  }

  @Override
  public String get_hover_info(Locale p_locale)
  {
    TextManager tm = new TextManager(this.getClass(), p_locale);

    String hover_info = tm.getText("via") + " " + tm.getText("padstack") + " : " + padstack.name + " " + tm.getText("layer") + " " + padstack.from_layer() + " " + tm.getText("to") + " " + tm.getText("layer") + " " + padstack.to_layer() + " " + this.get_connectable_item_hover_info(p_locale);

    return hover_info;
  }

  @Override
  public boolean write(ObjectOutputStream p_stream)
  {
    try
    {
      p_stream.writeObject(this);
    } catch (IOException e)
    {
      return false;
    }
    return true;
  }
}