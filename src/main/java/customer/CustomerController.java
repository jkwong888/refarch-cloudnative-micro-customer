package customer;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.cloudant.client.api.Database;
import com.cloudant.client.api.model.Response;
import com.cloudant.client.org.lightcouch.NoDocumentException;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;

import customer.model.Customer;

/**
 * REST Controller to manage Customer database
 *
 */
@RestController
public class CustomerController {

    private static Logger logger =  LoggerFactory.getLogger(CustomerController.class);

    @Autowired
    private Database cloudant;

    @PostConstruct
    private void init() throws MalformedURLException {
        // create the design document if it doesn't exist
		if (!cloudant.contains("_design/username_searchIndex")) {
		    final Map<String, Object> names = new HashMap<String, Object>();
		    names.put("index", "function(doc){index(\"usernames\", doc.username); }");

		    final Map<String, Object> indexes = new HashMap<>();
		    indexes.put("usernames", names);

		    final Map<String, Object> view_ddoc = new HashMap<>();
		    view_ddoc.put("_id", "_design/username_searchIndex");
		    view_ddoc.put("indexes", indexes);

		    cloudant.save(view_ddoc);
		}
    }

    /**
     * check
     */
    @RequestMapping("/check")
    @ResponseBody ResponseEntity<String> check() {
    	// test the cloudant connection
    	try {
			      cloudant.info();
            return  ResponseEntity.ok("It works!");
    	} catch (Exception e) {
    		logger.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
    	}
    }

    /**
     * @return customer by username
     */
    @PreAuthorize("#oauth2.hasScope('admin')")
    @RequestMapping(value = "/customer/search", method = RequestMethod.GET)
    @ResponseBody ResponseEntity<?> searchCustomers(@RequestHeader Map<String, String> headers, @RequestParam(required=true) String username) {
        try {

        	if (username == null) {
        		return ResponseEntity.badRequest().body("Missing username");
        	}

        	final List<Customer> customers = cloudant.findByIndex(
        			"{ \"selector\": { \"username\": \"" + username + "\" } }",
        			Customer.class);

        	//  query index
            return  ResponseEntity.ok(customers);

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

    }


    private String getCustomerId() {
    	final SecurityContext ctx = SecurityContextHolder.getContext();
    	if (ctx.getAuthentication() == null) {
    		return null;
    	};

    	if (!ctx.getAuthentication().isAuthenticated()) {
    		return null;
    	}

    	final OAuth2Authentication oauth = (OAuth2Authentication)ctx.getAuthentication();

    	logger.debug("CustomerID: " + oauth.getName());

    	return oauth.getName();
    }
     /**
     * @return all customer
     */
    @HystrixCommand(fallbackMethod="failGetCustomers")
    @RequestMapping(value = "/customer", method = RequestMethod.GET)
    ResponseEntity<?> getCustomers() {
        try {
        	final String customerId = getCustomerId();
        	if (customerId == null) {
        		// if no user passed in, this is a bad request
        		return ResponseEntity.badRequest().body("Invalid Bearer Token: Missing customer ID");
        	}

        	logger.debug("caller: " + customerId);
			final Customer cust = cloudant.find(Customer.class, customerId);

            return ResponseEntity.ok(Arrays.asList(cust));
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw e;
        }

    }

    /**
     * @return customer by id
     */
    @HystrixCommand(fallbackMethod="failGetCustomerById")
    @RequestMapping(value = "/customer/{id}", method = RequestMethod.GET)
    ResponseEntity<?> getById(@PathVariable String id) {
        try {
        	final String customerId = getCustomerId();
        	if (customerId == null) {
        		// if no user passed in, this is a bad request
        		return ResponseEntity.badRequest().body("Invalid Bearer Token: Missing customer ID");
        	}

        	logger.debug("caller: " + customerId);

        	if (!customerId.equals(id)) {
        		// if i'm getting a customer ID that doesn't match my own ID, then return 401
        		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        	}

          final Customer cust = cloudant.find(Customer.class, customerId);

          return ResponseEntity.ok(cust);
        } catch (NoDocumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Customer with ID " + id + " not found");
        }
    }

    /**
     * Add customer
     * @return transaction status
     */
    @RequestMapping(value = "/customer", method = RequestMethod.POST, consumes = "application/json")
    ResponseEntity<?> create(@RequestHeader Map<String, String> headers, @RequestBody Customer payload) {
      try {
        // TODO: no one should have access to do this, it's not exposed to APIC

        if (payload.getCustomerId() != null && cloudant.contains(payload.getCustomerId())) {
          return ResponseEntity.badRequest().body("Id " + payload.getCustomerId() + " already exists");
        }

        final List<Customer> customers = cloudant.findByIndex(
        "{ \"selector\": { \"username\": \"" + payload.getUsername() + "\" } }",
        Customer.class);

        if (!customers.isEmpty()) {
          return ResponseEntity.badRequest().body("Customer with name " + payload.getUsername() + " already exists");
        }

        // TODO: hash password
        //cust.setPassword(payload.getPassword());

        final Response resp = cloudant.save(payload);

        if (resp.getError() == null) {
          // HTTP 201 CREATED
          final URI location =  ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(resp.getId()).toUri();
          return ResponseEntity.created(location).build();
        } else {
          return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(resp.getError());
        }

      } catch (Exception ex) {
        logger.error("Error creating customer: " + ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error creating customer: " + ex.toString());
      }
    }


    /**
     * Update customer
     * @return transaction status
     */
    @RequestMapping(value = "/customer/{id}", method = RequestMethod.PUT, consumes = "application/json")
    ResponseEntity<?> update(@RequestHeader Map<String, String> headers, @PathVariable String id, @RequestBody Customer payload) {

        try {
        	final String customerId = getCustomerId();
        	if (customerId == null) {
        		// if no user passed in, this is a bad request
        		return ResponseEntity.badRequest().body("Invalid Bearer Token: Missing customer ID");
        	}

        	logger.info("caller: " + customerId);
			if (!customerId.equals("id")) {
        		// if i'm getting a customer ID that doesn't match my own ID, then return 401
        		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        	}

            final Customer cust = cloudant.find(Customer.class, id);

            cust.setFirstName(payload.getFirstName());
            cust.setLastName(payload.getLastName());
            cust.setImageUrl(payload.getImageUrl());
            cust.setEmail(payload.getEmail());

            // TODO: hash password
            cust.setPassword(payload.getPassword());

            cloudant.save(payload);
        } catch (NoDocumentException e) {
            logger.error("Customer not found: " + id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Customer with ID " + id + " not found");
        } catch (Exception ex) {
            logger.error("Error updating customer: " + ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error updating customer: " + ex.toString());
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Delete customer
     * @return transaction status
     */
    @RequestMapping(value = "/customer/{id}", method = RequestMethod.DELETE)
    ResponseEntity<?> delete(@RequestHeader Map<String, String> headers, @PathVariable String id) {
		// TODO: no one should have access to do this, it's not exposed to APIC

        try {
            final Customer cust = cloudant.find(Customer.class, id);
            cloudant.remove(cust);
        } catch (NoDocumentException e) {
            logger.error("Customer not found: " + id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Customer with ID " + id + " not found");
        } catch (Exception ex) {
            logger.error("Error deleting customer: " + ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error deleting customer: " + ex.toString());
        }
        return ResponseEntity.ok().build();
    }

    /* circuit breaker method
     * 
     */
    public ResponseEntity<?> failGetCustomers() {
        // Simply return an empty array
        final List<Customer> inventoryList = new ArrayList<Customer>();
        return ResponseEntity.ok(inventoryList);
    }
    
    /* circuit breaker method
     * 
     */
    public ResponseEntity<?> failGetCustomerById(@PathVariable String id) {
        // Simply return an empty array
		logger.error("Customer not found: " + id);
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Customer with ID " + id + " not found");
    }

}
